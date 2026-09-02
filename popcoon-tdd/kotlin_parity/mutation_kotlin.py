#!/usr/bin/env python3
"""mutation_kotlin.py — Kotlin 本番コードに欠陥を注入し、kotest spec が検出できるか測る。

背景 (2026-08):
  `run_kotest.sh` で 43 spec / 618 アサーションを実行できるようになった。しかし
  「通っている」ことと「検証している」ことは別である — 本セッションだけで
  **フィクスチャが不変条件を一度も踏んでいない** spec を 2 件見つけた
  (SaleCalendarTest / AdviceCacheTest、RESEARCH-2026-08 §7・§14)。
  Python 側は `mutation_test.py` で検出能力を測っているが、Kotlin 側は未測定だった。

  そこで同じ問いを Kotlin にも向ける: **意味のあるバグを注入したら spec は落ちるか。**

結果の読み方:
  Killed   = 注入が検出された (テストが実際に働いている)
  Survived = 注入がすり抜けた (**その挙動は誰も検証していない**)

  survived は「テストを増やすべき場所」の名指しであって、必ずしもバグではない。
  ただし本リポジトリの水準では survived をそのまま放置しない — spec を足すか、
  検証不能な理由を記録する。

高速化 (重要):
  素朴に毎回 `run_kotest.sh` を回すと 1 注入あたり 60〜90 秒かかる。ここでは
  **変異させた 1 ファイルだけを再コンパイル**し、生成 jar を core.jar より
  **前**に置く (JVM のクラスパスは先勝ち)。1 注入あたり数秒で済む。
  この方式が正しいことは、注入していない状態で全 spec が pass することで確認する
  (= baseline check。ここが落ちるなら測定系そのものが壊れている)。

  **例外 — `const val` は先勝ちでは差し替わらない**: Kotlin の `const val` は呼び出し側の
  バイトコードに**インライン展開**されるため、定数を宣言しているファイルだけを再コンパイルしても
  既にコンパイル済みの spec には反映されない。最初の実装はこれを見落として MU06
  (robots.txt の全許可化) を「生存」と誤報した — 実際には手動注入で kill されることを
  確認済みだった。`const val` を含む変異は自動判別して **フルパイプライン
  (run_kotest.sh) にフォールバック**する。測定ツール自身がこの種のバグを持つと
  「検証していない箇所」の地図が嘘になるので、ここは速度より正しさを取る。

使い方:
  python3 popcoon-tdd/kotlin_parity/mutation_kotlin.py           # 全件
  python3 popcoon-tdd/kotlin_parity/mutation_kotlin.py MU03      # 1 件だけ
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SRC = ROOT / "app/src/main/java/io/github/shizukutanaka/popcoon"

# (ID, 相対パス, 検索文字列, 置換文字列, 説明)
# 「意味のあるバグ」だけを入れる。等価変異 (挙動が変わらない書き換え) は測定を汚すので避ける。
MUTATIONS = [
    # ── ウィジェット / ウォッチリストの買い時判定 ─────────────────────────
    ("MU01", "feature/watchlist/WidgetVerdict.kt",
     "const val SIGNIFICANT_MOVE_PERCENT = 5",
     "const val SIGNIFICANT_MOVE_PERCENT = 50",
     "有意な値動きの閾値 5% → 50% (ほぼ全ての値下がりが BUY_NOW でなくなる)"),
    ("MU02", "feature/watchlist/WidgetVerdict.kt",
     "if (targetPrice != null && targetPrice > 0 && realPrice <= targetPrice) return BUY_NOW",
     "if (targetPrice != null && targetPrice > 0 && realPrice < targetPrice) return BUY_NOW",
     "目標価格ちょうど到達を買い時と見なさない (境界の off-by-one)"),
    ("MU03", "feature/watchlist/WidgetVerdict.kt",
     "if (realPrice <= 0) return NEUTRAL",
     "if (realPrice < 0) return NEUTRAL",
     "¥0 (取得失敗) を有効価格として扱う"),

    # ── ウィジェットのセール判定 ─────────────────────────────────────────
    ("MU04", "widget/PopcoonWidgetLogic.kt",
     "day == 10 || day == 20 || day == 30 ->",
     "day == 10 || day == 20 ->",
     "楽天 5と0のつく日から 30 日を落とす"),
    ("MU05", "widget/PopcoonWidgetLogic.kt",
     "return candidates.firstOrNull { it > today } ?: 5",
     "return candidates.firstOrNull { it >= today } ?: 5",
     "次のポイントアップ日に「今日」を含めてしまう"),

    # ── robots.txt (RFC 9309) ────────────────────────────────────────────
    ("MU06", "data/network/RobotsTxt.kt",
     'const val DENY_ALL_ROBOTS = "User-agent: *\\nDisallow: /"',
     'const val DENY_ALL_ROBOTS = "User-agent: *\\nDisallow: "',
     "取得不能時の合成 robots.txt を全許可にする"),

    # ── 価格グラフのデータ整形 ───────────────────────────────────────────
    ("MU07", "ui/components/PriceChartData.kt",
     "records.filter { it.realPrice > 0 }.sortedBy { it.recordedAt }",
     "records.sortedBy { it.recordedAt }",
     "グラフから ¥0 汚染レコードの除外を外す"),
    ("MU08", "ui/components/PriceChartData.kt",
     "return records.filter { it.recordedAt >= cutoff }",
     "return records.filter { it.recordedAt <= cutoff }",
     "期間フィルタの向きを反転 (古い方を残す)"),

    # ── CSV エクスポート ─────────────────────────────────────────────────
    ("MU09", "feature/export/CsvEscape.kt",
     'val escaped = guarded.replace("\\"", "\\"\\"")',
     "val escaped = guarded",
     "CSV のダブルクォートエスケープを外す (RFC 4180 違反)"),

    # ── ダークパターン検出 ───────────────────────────────────────────────
    ("MU10", "feature/darkpattern/DarkPatternDetector.kt",
     "warnings.sortedByDescending { it.severity.ordinal }",
     "warnings.sortedBy { it.severity.ordinal }",
     "警告の深刻度並べ替えを逆順にする"),

    # ── TCO ───────────────────────────────────────────────────────────────
    ("MU11", "feature/tco/TCOCalculator.kt",
     "val drum = (8000 * 0.33).toLong()",
     "val drum = (8000 * 0.33 * intensity).toLong()",
     "ドラムを intensity に比例させる (修正済みの旧バグを再導入)"),
    ("MU12", "feature/tco/TCOCalculator.kt",
     'if (ACCESSORY_MARKERS.any { t.contains(it) }) return null',
     "// mutated: accessory guard removed",
     "TCO カテゴリ推定の付属品ガードを外す"),

    # ── セールカレンダー ─────────────────────────────────────────────────
    ("MU13", "feature/calendar/SaleCalendar.kt",
     ".sortedBy { it.tier.ordinal }",
     ".sortedByDescending { it.tier.ordinal }",
     "活性セールの並びを重要度の逆順にする"),

    # ── PII サニタイズ ───────────────────────────────────────────────────
    # 注: LogSanitizer は **kotest ではなく run_sanitizer.sh (共有コーパス)** が守っている。
    # このツールの測定範囲は kotest spec なので MU14 は「生存」と出るが、それは
    # 「誰も検証していない」ではなく「別のハーネスが検証している」の意味。
    # 二重に持たせず、結果表の注記で扱う (RESEARCH-2026-08 §15)。
    ("MU14", "core/LogSanitizer.kt",
     '.replace(Regex("""\\b0\\d{1,4}[-\\s]?\\d{1,4}[-\\s]?\\d{4}\\b"""), "[tel]")',
     "",
     "国内電話番号の除去パターンを削除"),

    # ── 通知の優先度付け ─────────────────────────────────────────────────
    ("MU15", "worker/PriceSyncPlanner.kt",
     "compareByDescending<Drop> { it.targetReached }.thenByDescending { it.pct }",
     "compareBy<Drop> { it.targetReached }.thenBy { it.pct }",
     "通知の優先度を逆転させる (目標到達が最後に回る)"),
]


def find_toolchain():
    kc = None
    for pat in ["/opt/gradle-*/lib", os.path.expanduser("~/.gradle")]:
        out = subprocess.run(["bash", "-c", f"find {pat} -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | head -1"],
                             capture_output=True, text=True).stdout.strip()
        if out:
            kc = out
            break
    if not kc:
        sys.exit("ERROR: kotlin-compiler-embeddable が見つからない")
    lib = os.path.dirname(kc)

    def one(pattern, exclude=None):
        cmd = f"find {lib} -name '{pattern}'" + (f" | grep -v {exclude}" if exclude else "") + " | head -1"
        return subprocess.run(["bash", "-c", cmd], capture_output=True, text=True).stdout.strip()

    st = one("kotlin-stdlib-2*.jar", "sources")
    ser = one("kotlinx-serialization-core-jvm-*.jar") + ":" + one("kotlinx-serialization-json-jvm-*.jar")
    return lib, st, ser


def main() -> int:
    only = sys.argv[1] if len(sys.argv) > 1 else None
    lib, st, ser = find_toolchain()
    work = tempfile.mkdtemp(prefix="mutkt-")

    # 1. baseline: 変異なしで core.jar / shim.jar / specs.jar を作る
    print("baseline を構築中 (run_kotest.sh)...", flush=True)
    env = dict(os.environ, POPCOON_KOTEST_OUT=work)
    base = subprocess.run(["bash", str(HERE / "run_kotest.sh")], capture_output=True, text=True, env=env)
    if "0 failed" not in base.stdout:
        print(base.stdout[-2000:], file=sys.stderr)
        sys.exit("ERROR: baseline が緑でない。先に run_kotest.sh を直すこと。")
    m = re.search(r"KOTEST SHIM: (\d+) specs / (\d+) passed", base.stdout)
    print(f"baseline: {m.group(1)} specs / {m.group(2)} passed / 0 failed\n")

    core, shim, specs = f"{work}/core.jar", f"{work}/shim.jar", f"{work}/specs.jar"
    killed, survived, skipped = [], [], []

    for mid, rel, find, repl, desc in MUTATIONS:
        if only and not mid.startswith(only):
            continue
        path = SRC / rel
        original = path.read_text(encoding="utf-8")
        if find not in original:
            skipped.append((mid, desc, "対象コードが見つからない (実装が変わった可能性)"))
            print(f"  {mid} SKIP  {desc}")
            continue
        assert original.count(find) == 1, f"{mid}: 対象が一意でない"

        # const val はインライン展開されるので速い経路が使えない (docstring 参照)。
        slow = "const val" in find
        path.write_text(original.replace(find, repl, 1), encoding="utf-8")
        try:
            if slow:
                r = subprocess.run(["bash", str(HERE / "run_kotest.sh")],
                                   capture_output=True, text=True, env=dict(os.environ))
                if "0 failed" in r.stdout:
                    survived.append((mid, desc))
                    print(f"  {mid} SURV  {desc}   ← この挙動を検証している spec が無い  [full]")
                else:
                    killed.append((mid, desc))
                    print(f"  {mid} KILL  {desc}  [full]")
                continue
            # 変異ファイルだけ再コンパイル。core.jar を classpath に置いて解決する。
            mut_jar = f"{work}/mutant.jar"
            if os.path.exists(mut_jar):
                os.remove(mut_jar)
            cc = subprocess.run(
                ["java", "-cp", f"{lib}/*", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                 "-cp", f"{st}:{ser}:{core}", "-d", mut_jar, "-nowarn", str(path)],
                capture_output=True, text=True)
            if not os.path.exists(mut_jar):
                skipped.append((mid, desc, "変異後にコンパイルできない (= コンパイラが検出)"))
                print(f"  {mid} KILL* {desc}  [コンパイルエラーで検出]")
                killed.append((mid, desc))
                continue
            # mutant.jar を core.jar より前に置く (先勝ち)
            run = subprocess.run(
                ["java", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
                 "-cp", f"{specs}:{mut_jar}:{core}:{shim}:{st}:{ser}", "GeneratedSpecListKt"],
                capture_output=True, text=True)
            if run.returncode != 0:
                killed.append((mid, desc))
                print(f"  {mid} KILL  {desc}")
            else:
                survived.append((mid, desc))
                print(f"  {mid} SURV  {desc}   ← この挙動を検証している spec が無い")
        finally:
            path.write_text(original, encoding="utf-8")

    total = len(killed) + len(survived)
    print(f"\nMUTATION (Kotlin): {len(killed)} killed / {len(survived)} survived"
          f" / {len(skipped)} skipped  → 検出率 {100 * len(killed) // total if total else 0}%")
    if survived:
        print("\n生存した変異 (= 誰も検証していない挙動):", file=sys.stderr)
        for mid, desc in survived:
            print(f"  {mid}: {desc}", file=sys.stderr)
    for mid, desc, why in skipped:
        print(f"  [skip] {mid}: {desc} — {why}", file=sys.stderr)
    shutil.rmtree(work, ignore_errors=True)
    return 1 if survived else 0


if __name__ == "__main__":
    sys.exit(main())
