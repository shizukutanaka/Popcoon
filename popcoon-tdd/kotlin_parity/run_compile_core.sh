#!/usr/bin/env bash
# run_compile_core.sh — Android SDK 無しで **app モジュールの Android 非依存部分を実コンパイル** する。
#
# 背景 (2026-08):
#   `UserPreferences : IUserPreferences` の 5 メンバーが `override` 欠落のまま約 1 か月
#   コンパイル不能だった (e519e67)。Android SDK が無く CI も未稼働のため、既存の parity
#   ハーネス (特定の関数を実行して Python と照合する) では型レベルの破綻を検出できなかった。
#
# このハーネスが捕まえるもの:
#   - インタフェース実装の override 欠落 / シグネチャ不一致
#   - enum・sealed に対する when の網羅漏れ (Kotlin 2.x では error)
#   - 未解決参照、型不一致、可視性違反
#   - `R.string.*` の未定義 (values/strings.xml から R スタブを生成して突き合わせる)
#
# 対象は「依存 jar が手元にあるファイル」のみ (自動判定)。Gradle ディストリビューションが
# 同梱する kotlin-stdlib / kotlinx-serialization / kotlinx-coroutines / javax.inject までは
# 実物を使えるので、それらに依存するファイルもコンパイルする。
# Android/AndroidX/Hilt(dagger)/ktor に依存する残りの層は依然ビルド検証不能 —
# CI 有効化 (ci/README.md) が必要。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

KC="$(find "$HOME/.gradle" ${GRADLE_HOME:+"$GRADLE_HOME/lib"} /opt/gradle-*/lib /usr/share/gradle*/lib -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | head -1 || true)"
if [[ -z "$KC" ]]; then
  echo "ERROR: kotlin-compiler-embeddable not found; run './gradlew --version' once." >&2; exit 2
fi
LIB="$(dirname "$KC")"
ST="$(find "$LIB" -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1)"
SER="$(find "$LIB" -name 'kotlinx-serialization-core-jvm-*.jar' | head -1):$(find "$LIB" -name 'kotlinx-serialization-json-jvm-*.jar' | head -1)"
# Gradle ディストリビューションは coroutines と javax.inject の **実 jar** も同梱している。
# 以前の対象選定はこの 2 つを「依存 jar が無い」側に分類していたが事実誤認だった
# (2026-08 に /opt/gradle-*/lib を実地確認)。スタブではなく実物なので、
# これらに依存するファイルも本物の型検査ができる。
# 注: 同梱の coroutines は 1.6.4、プロジェクトの指定は 1.9.0。API は互換だが、
# 1.7+ 固有の API を使い始めるとここが先に落ちる (偽の失敗は目に見えるので許容 —
# 危険なのは偽の成功の方)。
COR="$(find "$LIB" -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)"
INJ="$(find "$LIB" -name 'javax.inject-*.jar' | head -1)"

# 1. 対象ファイルの選定 + values/strings.xml から R スタブを生成
python3 - "$SRC" "$ROOT/app/src/main/res/values/strings.xml" "$OUT" <<'PY'
import io, os, pathlib, re, sys
src, strings_xml, out = sys.argv[1], sys.argv[2], sys.argv[3]

# 対象集合は「閉じている」必要がある。3 条件の不動点で求める:
#   (a) 依存 jar が手元に無いものを import しない
#       (Android / AndroidX / Hilt(dagger) / ktor。coroutines と javax.inject は
#        Gradle 同梱の実 jar があるので **対象に含める**)
#   (b) プロジェクト内 import の相手が全て対象集合に入っている
#   (c) 同一パッケージで対象外になったファイルの **トップレベル宣言を参照していない**
#       (同パッケージの型は import 無しで参照できるため。パッケージ丸ごと落とすと
#        ui/*Labels.kt のような検証価値の高いファイルまで巻き添えになるので、
#        実際に参照しているかで判定する)
# 判定を誤って過剰に含めた場合はコンパイルが **失敗して顕在化** する (黙って
# カバレッジが減る方向には倒れない)。
PKG = "io.github.shizukutanaka.popcoon"
dep = re.compile(r"^import (android[.x]|androidx\.|dagger\.|com\.google\.|io\.ktor)", re.M)
proj_import = re.compile(r"^import (" + re.escape(PKG) + r"\.[A-Za-z0-9_.]+)", re.M)

# トップレベル宣言の名前。**拡張関数のレシーバを宣言名と読まないこと**が要点:
# 素朴に `fun\s+(\w+)` とすると `internal fun String.escapeCsv()` から `String` を、
# `fun WatchlistBackupEntry.toWatchlistItem()` から `WatchlistBackupEntry` を
# 「このファイルが宣言している名前」として拾ってしまう。すると条件 (c) 側で
# 同パッケージの全ファイルが `String` を参照しているとみなされ、パッケージごと
# 検証対象から消える (WatchlistBackupEntry.kt が実際にこれで落ちた)。
# レシーバ (`Foo.` の部分) は任意でスキップし、実際の宣言名だけを取る。
_MODIFIERS = (r"^(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+)?"
              r"(?:abstract\s+|sealed\s+|open\s+|data\s+|value\s+)*")
decl_type = re.compile(_MODIFIERS + r"(?:class|object|interface|enum class|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)", re.M)
decl_fun = re.compile(_MODIFIERS + r"(?:suspend\s+)?fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?, ]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(", re.M)
decl_prop = re.compile(_MODIFIERS + r"(?:val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?, ]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*[:=]", re.M)


def top_level_decls(text):
    return (set(decl_type.findall(text)) | set(decl_fun.findall(text))
            | set(decl_prop.findall(text)))

def strip_noncode(s):
    """コメントと文字列リテラルを落とす。

    条件 (c) の「同一パッケージの対象外宣言を参照しているか」は素朴に本文全体を
    正規表現で走査していたため、**KDoc に型名を書いただけで参照とみなされ**、
    そのファイルが検証対象から外れていた (PriceSyncPlanner.kt が実例: 説明文中の
    `PriceSyncWorker` に反応して落ちていた)。ドキュメントを書くほど型検査の
    カバレッジが減るのは明らかに逆で、コード部分だけを見る。
    """
    s = re.sub(r"/\*.*?\*/", " ", s, flags=re.S)
    s = re.sub(r"//[^\n]*", " ", s)
    s = re.sub(r'"""(?:.|\n)*?"""', ' "" ', s)
    s = re.sub(r'"(?:\\.|[^"\\\n])*"', ' "" ', s)
    return s


# ── Room エンティティのスタブ生成 ───────────────────────────────────────────
# `data/db/PopcoonDatabase.kt` は RoomDatabase / Dao / Migration という **実クラス** を
# 要求するのでコンパイルできない。しかし同居している `@Entity data class` が要求するのは
# `@Entity` / `@Index` / `@PrimaryKey` の **アノテーションだけ** で、これらはプロセッサ不在では
# 実物も不活性なのでスタブが嘘をつけない (ASSESSMENT の判定と同じ)。
#
# エンティティが DB クラスと同居しているせいで `data.db` パッケージ全体が Android 依存と
# 見なされ、それを import する **純ロジックのファイルまで巻き添え** になっていた
# (実測: SmartCartService.kt / WatchlistSort.kt)。
#
# そこで RStub.kt と同じ方式 — **実ファイルから毎回切り出して生成** する。コピーではなく
# 実ソースの部分集合なので、本体を変更すれば次回の実行に自動で反映される (ドリフト不可能)。
DB_FILE = os.path.join(src, "io/github/shizukutanaka/popcoon/data/db/PopcoonDatabase.kt")
if not os.path.exists(DB_FILE):
    DB_FILE = os.path.join(src, "data/db/PopcoonDatabase.kt")
_db_lines = io.open(DB_FILE, encoding="utf-8").read().split("\n")
_entity_blocks, _entity_names = [], []
_i = 0
while _i < len(_db_lines):
    if _db_lines[_i].startswith("@Entity"):
        _start = _i
        while _i < len(_db_lines) and not re.match(r"data class (\w+)\(", _db_lines[_i]):
            _i += 1
        _entity_names.append(re.match(r"data class (\w+)\(", _db_lines[_i]).group(1))
        _depth = _db_lines[_i].count("(") - _db_lines[_i].count(")")
        _i += 1
        while _i < len(_db_lines) and _depth > 0:
            _depth += _db_lines[_i].count("(") - _db_lines[_i].count(")")
            _i += 1
        _entity_blocks.append("\n".join(_db_lines[_start:_i]))
    else:
        _i += 1
if not _entity_names:
    raise SystemExit("ERROR: PopcoonDatabase.kt から @Entity を 1 つも切り出せなかった")

io.open(os.path.join(out, "RoomAnnotationStub.kt"), "w", encoding="utf-8").write(
    "package androidx.room\n\n"
    "// Room アノテーションのスタブ。プロセッサ不在では実物も不活性なので意味的に同一。\n"
    "annotation class Entity(val tableName: String = \"\", val indices: Array<Index> = [])\n"
    "annotation class Index(val value: Array<String> = [])\n"
    "annotation class PrimaryKey(val autoGenerate: Boolean = false)\n"
    "annotation class ColumnInfo(val name: String = \"\", val defaultValue: String = \"\")\n"
    "annotation class Ignore\n")

ENTITY_STUB = os.path.join(out, "EntityStub.kt")
io.open(ENTITY_STUB, "w", encoding="utf-8").write(
    f"package {PKG}.data.db\n\n"
    "import androidx.room.Entity\n"
    "import androidx.room.Index\n"
    "import androidx.room.PrimaryKey\n"
    "import androidx.room.ColumnInfo\n"
    "import java.time.Instant\n\n"
    "// PopcoonDatabase.kt から自動生成 (コピーではなく毎回の切り出し)。手で編集しないこと。\n\n"
    + "\n\n".join(_entity_blocks) + "\n")

info = {}
for f in sorted(pathlib.Path(src).rglob("*.kt")):
    text = io.open(f, encoding="utf-8").read()
    m = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.M)
    info[str(f)] = {
        "text": text,
        "code": strip_noncode(text),
        "pkg": m.group(1) if m else "",
        "android": bool(dep.search(text)),
        # import 先の FQN から末尾の型名を落としてパッケージを得る (R スタブは除外)
        "imports": {i.rsplit(".", 1)[0] for i in proj_import.findall(text) if i != f"{PKG}.R"},
        "decls": top_level_decls(text),
    }

# 生成したエンティティスタブを「Android 非依存で data.db パッケージを提供するファイル」として
# 合成登録する。これにより data.db を import する純ロジックのファイルが (b) を通過できる。
# DAO 等 **提供していない宣言** を参照するファイルが混ざれば実コンパイルが失敗して顕在化する
# (このスクリプトの設計方針どおり「黙ってカバレッジが減る方向」には倒れない)。
info[ENTITY_STUB] = {
    "text": "", "code": "", "pkg": f"{PKG}.data.db", "android": False,
    "imports": set(), "decls": set(_entity_names),
}

keep = {f for f, v in info.items() if not v["android"]}
while True:
    pkgs_kept = {info[f]["pkg"] for f in keep}
    # 対象外ファイルがパッケージごとに公開しているトップレベル名
    dropped_decls = {}
    for f, v in info.items():
        if f not in keep:
            dropped_decls.setdefault(v["pkg"], set()).update(v["decls"])
    drop = set()
    for f in keep:
        v = info[f]
        # (b) import 先パッケージが対象に含まれない
        if not v["imports"] <= pkgs_kept:
            drop.add(f)
            continue
        # (c) 同一パッケージの対象外宣言を実際に参照している
        siblings = dropped_decls.get(v["pkg"], set()) - v["decls"]
        if any(re.search(r"\b" + re.escape(n) + r"\b", v["code"]) for n in siblings):
            drop.add(f)
    if not drop:
        break
    keep -= drop

targets = sorted(keep)
io.open(os.path.join(out, "targets.txt"), "w").write("\n".join(targets))

# aapt が生成する R の代わりに、実際の strings.xml から const を起こす。
# これにより「コードが参照する R.string.X が strings.xml に無い」も同時に検出できる。
names = re.findall(r'<string name="([^"]+)"', io.open(strings_xml, encoding="utf-8").read())
pkg = "io.github.shizukutanaka.popcoon"
lines = [f"package {pkg}", "", "/** aapt 生成 R の代替スタブ (values/strings.xml から自動生成)。 */",
         "object R {", "    object string {"]
lines += [f"        const val {n}: Int = {i}" for i, n in enumerate(sorted(set(names)))]
lines += ["    }", "}", ""]
io.open(os.path.join(out, "RStub.kt"), "w", encoding="utf-8").write("\n".join(lines))
print(f"compile targets: {len(targets)} files / R.string stubs: {len(set(names))}")
PY

# 2. 実コンパイル
mapfile -t TARGETS < "$OUT/targets.txt"
java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$ST:$SER:$COR:$INJ" -d "$OUT/core.jar" -nowarn -no-reflect \
  "$OUT/RStub.kt" "$OUT/RoomAnnotationStub.kt" "${TARGETS[@]}" 2>&1 | grep -v 'unable to find kotlin' || true

# run_kotest.sh 用に、生成した core.jar と R スタブを外へ渡す (指定時のみ)。
# kotest シムは同じ production クラス群に対してテストを走らせる必要があり、
# 対象ファイルの選定規則をここと二重管理したくない。
if [[ -n "${POPCOON_CORE_JAR_OUT:-}" ]]; then
  cp "$OUT/core.jar" "$POPCOON_CORE_JAR_OUT"
fi

# 対象ファイル数の下限。Android 依存 import が増えると自動判定で対象が減るため、
# 「黙ってカバレッジが縮む」ことを検知する。意図的に減らす場合はこの値も更新すること。
MIN_TARGETS=54
if [[ ${#TARGETS[@]} -lt $MIN_TARGETS ]]; then
  echo "CORE COMPILE: coverage shrank (${#TARGETS[@]} < $MIN_TARGETS files)." >&2
  echo "  Android/AndroidX/Hilt(dagger)/ktor への依存が増えていないか確認し、" >&2
  echo "  意図的なら MIN_TARGETS を更新すること。" >&2
  exit 1
fi

# 3. コンパイル対象外 (Android/Hilt 依存) のファイルも含めた override 欠落の静的検査。
#    実コンパイルできるのは 46/131 ファイルだけで、2026-08 に実際に壊れた
#    UserPreferences.kt は datastore + dagger 依存で対象外のまま。この検査は
#    全ソースを構文的に走査してその回帰クラスだけを塞ぐ。
python3 "$HERE/check_overrides.py" || exit 1

# 4. 全ソースの `R.*` 参照がリソースに実在するかの静的検査。
#    上の R スタブは values/strings.xml から起こすので対象 46 ファイルの
#    R.string.* しか見ておらず、残り 85 ファイルの参照と
#    R.drawable / R.color / R.plurals / R.xml / R.style / R.mipmap / R.id は
#    どこからも検査されていなかった。未定義参照は assembleDebug で確実に
#    コンパイルエラーになる = CI を有効化した瞬間に赤くなる類の欠陥。
python3 "$HERE/check_resources.py" || exit 1

# 5. enum に対する `when` の網羅漏れの静的検査。Kotlin 2.x では非網羅的な when は
#    エラーなので、これも「CI を有効化した瞬間に赤くなる」欠陥クラス。実コンパイル
#    対象の 46 ファイルはコンパイラが見るが、残り 85 ファイル (Compose/Glance/Room 等)
#    は誰も見ていなかった。実例: Category に OBSTRUCTION を足したとき
#    ui/DarkPatternTextLabels.kt の when も同時更新が必要だった。
python3 "$HERE/check_when_exhaustive.py" || exit 1

# 6. テスト (app/src/test の 64 ファイル) が参照する本番シンボルの実在検査。
#    kotest の jar が無いのでテストは 1 ファイルもコンパイルできない。本番 API を
#    改名したときの追随漏れは CI 初回実行まで誰にも見えない — `Object.member` の
#    member が存在するかだけは静的に決まるので、そこだけ塞ぐ。
python3 "$HERE/check_test_refs.py" || exit 1

# 7. `realPrice` を統計に使うファイルが ¥0 を除外しているかの検査 (歯止め)。
#    「取得失敗を 0 円として記録したレコード」による判定破壊を 2026-08 に 8 経路で
#    見つけて直した (買い時スコア / 予測 / 週次ダイジェスト / グラフ / 目標チップ /
#    スマートカート / ダークパターン検出 / 各種ソート)。9 回目を人間の注意力に
#    頼らないための規則化。
python3 "$HERE/check_price_guard.py" || exit 1

# 8. Room の移行チェーン検査。移行ミスは「更新したら二度と起動しない」クラスの障害で、
#    release ビルドはユーザーデータ保全のため破壊的フォールバックを切っている。
#    この環境では Room を一切実行できない (androidx 不在 / instrumentation 不可) が、
#    「宣言したのに addMigrations() へ登録し忘れ」「version を上げてチェーン断絶」は
#    静的に決まる。
python3 "$HERE/check_migrations.py" || exit 1

# 9. 「上限を掛ける前に優先順位を付けているか」検査。同じ形の欠陥を 2 回見つけた:
#    通知上限が超過分を破棄して二度と再通知されなくなった件 (RESEARCH-2026-08 §6) と、
#    検索結果の警告 take(2) が支払額 3 割増の DRIP_PRICING(HIGH) を
#    CHARM_PRICING(LOW) に押し出していた件 (§12)。どちらもユーザーに見える情報が
#    静かに落ちる。3 回目を人間の注意力に頼らないための規則化。
python3 "$HERE/check_truncation.py" || exit 1

# 10. ビルド構成の整合検査。この環境では `./gradlew` を動かせず、CI 有効化も人手ゲート
#     (GitHub App に `workflows` 権限が無いことを再測定で確認) なので、
#     **最初の CI 実行が初めての実ビルド**になる。型検査はソースしか見ておらず、
#     version catalog / Manifest / リソース XML / ワークフロー YAML は誰も見ていなかった。
#     初回実行が即死する類の設定ミスは静的に決まるので先に潰す。
python3 "$HERE/check_build_config.py" || exit 1

# 11. i18n の整合検査。既存の i18n ゲート (ci/verify.py) は **キーの個数**しか見ておらず、
#     ja に A / en に B があっても通ってしまう。書式指定子がずれれば
#     getString(id, args) が実行時例外を投げる。「4 ロケール完全一致」は ASSESSMENT が
#     長所として掲げる性質なので、その主張に見合う検査にする。
python3 "$HERE/check_i18n.py" || exit 1

if [[ -f "$OUT/core.jar" ]]; then
  echo "CORE COMPILE: OK (${#TARGETS[@]} files)"
else
  echo "CORE COMPILE: FAILED" >&2
  exit 1
fi
