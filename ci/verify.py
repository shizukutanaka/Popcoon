#!/usr/bin/env python3
"""verify.py — 全検証ゲートを 1 コマンドで実行し、CLAUDE.md の基準線と突き合わせる。

CLAUDE.md の「検証コマンド」表は 5 つの独立したコマンドを人手で叩き、出てきた数値を
目視で表と比べる運用だった。実際にはドリフトが常態化していた (2026-08 の作業中だけで
oracle 件数 / parity 件数 / i18n キー数の同期漏れを 6 回以上手で直している)。

このスクリプトは:
  1. 依存を確認し、足りなければ何を入れるか明示してから入れる
  2. 全ゲートを実行する
  3. **CLAUDE.md 自身に書かれた基準線をパースして実測値と比較する**
     (新しい真実の源を増やさない — CLAUDE.md が引き続き単一の источник)
  4. 1 つでも不一致・失敗があれば exit 1

  `--update` を付けると CLAUDE.md の数値を実測値へ書き換える (意図的な変更後に使う)。

使い方:
    python3 ci/verify.py            # 検証 + 基準線照合
    python3 ci/verify.py --update   # 基準線を実測値へ同期
    python3 ci/verify.py --skip-backend   # npm が使えない環境向け
"""
from __future__ import annotations

import argparse
import io
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLAUDE_MD = ROOT / "CLAUDE.md"
LOCALES = ["values", "values-en", "values-ko", "values-zh-rCN"]


def run(cmd, cwd=None, timeout=1800):
    p = subprocess.run(cmd, shell=True, cwd=cwd or ROOT, capture_output=True,
                       text=True, timeout=timeout)
    return p.returncode, p.stdout + p.stderr


def ensure_deps(skip_backend: bool) -> list[str]:
    notes = []
    rc, _ = run("python3 -c 'import pytest, hypothesis, pytest_benchmark'")
    if rc != 0:
        notes.append("pytest / hypothesis / pytest-benchmark を pip install しました")
        run("python3 -m pip install -q pytest hypothesis pytest-benchmark pytest-repeat pytest-xdist")
    if not skip_backend and not (ROOT / "backend/node_modules").is_dir():
        notes.append("backend の npm install を実行しました")
        run("npm install --silent", cwd=ROOT / "backend", timeout=900)
    return notes


def gate_pytest():
    rc, out = run("python3 -m pytest -q", cwd=ROOT / "popcoon-tdd")
    m = re.search(r"(\d+) passed(?:, (\d+) skipped)?", out)
    if not m:
        return None, f"pytest の出力を解釈できませんでした\n{out[-800:]}"
    passed, skipped = int(m.group(1)), int(m.group(2) or 0)
    return {"passed": passed, "skipped": skipped}, None if rc == 0 else out[-800:]


def gate_parity():
    rc, out = run("bash popcoon-tdd/kotlin_parity/run_all.sh")
    harnesses = len(re.findall(r"✓ ", out))
    m_match = re.search(r"PARITY: (\d+) matched, (\d+) mismatched", out)
    m_core = re.search(r"CORE COMPILE: OK \((\d+) files\)", out)
    data = {
        "harnesses": harnesses,
        "matched": int(m_match.group(1)) if m_match else -1,
        "mismatched": int(m_match.group(2)) if m_match else -1,
        "core_files": int(m_core.group(1)) if m_core else -1,
    }
    ok = rc == 0 and "all passed" in out and data["mismatched"] == 0
    return data, None if ok else out[-1500:]


def gate_backend():
    rc_tsc, out_tsc = run("npx tsc --noEmit", cwd=ROOT / "backend", timeout=900)
    if rc_tsc != 0:
        return None, f"tsc エラー:\n{out_tsc[-800:]}"
    rc, out = run("npx vitest run", cwd=ROOT / "backend", timeout=900)
    m = re.search(r"Tests\s+(\d+) passed", out)
    mf = re.search(r"Test Files\s+(\d+) passed", out)
    data = {"tests": int(m.group(1)) if m else -1, "files": int(mf.group(1)) if mf else -1}
    return data, None if rc == 0 else out[-1000:]


def gate_i18n():
    counts, plurals = {}, {}
    for loc in LOCALES:
        text = io.open(ROOT / f"app/src/main/res/{loc}/strings.xml", encoding="utf-8").read()
        counts[loc] = len(re.findall(r'<string name="', text))
        plurals[loc] = len(re.findall(r'<plurals name="', text))
    uniq, uniq_p = set(counts.values()), set(plurals.values())
    err = None
    if len(uniq) != 1:
        err = f"ロケール間でキー数が不一致: {counts}"
    elif len(uniq_p) != 1:
        err = f"ロケール間で plurals 数が不一致: {plurals}"
    return {"keys": next(iter(uniq)), "plurals": next(iter(uniq_p))}, err


def parse_baseline(md: str) -> dict:
    b = {}
    m = re.search(r"\*\*(\d+) passed, (\d+) skipped\*\*", md)
    if m:
        b["pytest"] = {"passed": int(m.group(1)), "skipped": int(m.group(2))}
    m = re.search(r"全 (\d+) ハーネス pass.*?`run\.sh` は (\d+) matched / (\d+) mismatched"
                  r".*?`run_compile_core\.sh` は (\d+) ファイル", md)
    if m:
        b["parity"] = {"harnesses": int(m.group(1)), "matched": int(m.group(2)),
                       "mismatched": int(m.group(3)), "core_files": int(m.group(4))}
    m = re.search(r"\*\*(\d+) tests / (\d+) files pass\*\*", md)
    if m:
        b["backend"] = {"tests": int(m.group(1)), "files": int(m.group(2))}
    m = re.search(r"\*\*全ロケール (\d+)\*\* \(plurals (\d+) は別\)", md)
    if m:
        b["i18n"] = {"keys": int(m.group(1)), "plurals": int(m.group(2))}
    return b


def apply_baseline(md: str, actual: dict) -> str:
    if "pytest" in actual:
        md = re.sub(r"\*\*\d+ passed, \d+ skipped\*\*",
                    f"**{actual['pytest']['passed']} passed, {actual['pytest']['skipped']} skipped**", md, 1)
    if "parity" in actual:
        p = actual["parity"]
        md = re.sub(r"全 \d+ ハーネス pass \(`run\.sh` は \d+ matched / \d+ mismatched、"
                    r"`run_compile_core\.sh` は \d+ ファイル実コンパイル\)",
                    f"全 {p['harnesses']} ハーネス pass (`run.sh` は {p['matched']} matched / "
                    f"{p['mismatched']} mismatched、`run_compile_core.sh` は {p['core_files']} ファイル実コンパイル)",
                    md, 1)
    if "backend" in actual:
        md = re.sub(r"\*\*\d+ tests / \d+ files pass\*\*",
                    f"**{actual['backend']['tests']} tests / {actual['backend']['files']} files pass**", md, 1)
    if "i18n" in actual:
        md = re.sub(r"\*\*全ロケール \d+\*\* \(plurals \d+ は別\)",
                    f"**全ロケール {actual['i18n']['keys']}** (plurals {actual['i18n']['plurals']} は別)", md, 1)
    return md


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--update", action="store_true", help="CLAUDE.md の基準線を実測値へ同期")
    ap.add_argument("--skip-backend", action="store_true", help="backend ゲートを飛ばす")
    args = ap.parse_args()

    for note in ensure_deps(args.skip_backend):
        print(f"[setup] {note}")

    actual, failures = {}, []
    gates = [("pytest", gate_pytest), ("parity", gate_parity), ("i18n", gate_i18n)]
    if not args.skip_backend:
        gates.insert(2, ("backend", gate_backend))

    for name, fn in gates:
        data, err = fn()
        if data is not None:
            actual[name] = data
        if err:
            failures.append(f"{name}: {err}")

    md = io.open(CLAUDE_MD, encoding="utf-8").read()
    baseline = parse_baseline(md)

    print(f"\n{'ゲート':10s} {'実測':44s} {'基準線 (CLAUDE.md)':44s} 判定")
    print("-" * 112)
    drift = []
    for name in ("pytest", "parity", "backend", "i18n"):
        if name not in actual:
            continue
        a, b = actual[name], baseline.get(name)
        if b is None:
            verdict = "基準線を解釈できず"
        elif a == b:
            verdict = "OK"
        else:
            verdict = "*** ドリフト ***"
            drift.append(f"{name}: 実測 {a} != 基準線 {b}")
        print(f"{name:10s} {str(a):44s} {str(b):44s} {verdict}")

    if args.update and drift:
        io.open(CLAUDE_MD, "w", encoding="utf-8").write(apply_baseline(md, actual))
        print("\n[update] CLAUDE.md の基準線を実測値へ同期しました:")
        for d in drift:
            print("  " + d)
        drift = []

    print()
    if failures:
        print("VERIFY: FAILED", file=sys.stderr)
        for f in failures:
            print("  " + f, file=sys.stderr)
        return 1
    if drift:
        print("VERIFY: BASELINE DRIFT", file=sys.stderr)
        for d in drift:
            print("  " + d, file=sys.stderr)
        print("  意図的な変更なら `python3 ci/verify.py --update` で同期すること。", file=sys.stderr)
        return 1
    print("VERIFY: すべてのゲートが基準線どおり pass")
    return 0


if __name__ == "__main__":
    sys.exit(main())
