"""Mutation testing for buy_timing_scorer."""
import subprocess
import sys
import shutil
import re
from pathlib import Path

ROOT = Path("/home/claude/popcoon-tdd")
TARGET = ROOT / "buy_timing_scorer.py"
BACKUP = ROOT / "buy_timing_scorer.py.orig"

MUTATIONS = [
    ("BTS01_min_history_reduced",
     r"_MIN_HISTORY = 14",
     r"_MIN_HISTORY = 10",
     "最小履歴長 14→10"),

    ("BTS02_base_score_zero",
     r"_BASE_SCORE = 50",
     r"_BASE_SCORE = 0",
     "中立スコア 50→0"),

    ("BTS03_atl_reward_halved",
     r'return TimingSignal\("過去最安値到達", 30\)',
     r'return TimingSignal("過去最安値到達", 15)',
     "ATL到達の報酬を30→15に"),

    ("BTS04_high_price_penalty_weak",
     r'return TimingSignal\("過去最高値圏", -15\)',
     r'return TimingSignal("過去最高値圏", -5)',
     "高値圏罰則を-15→-5に"),

    ("BTS05_dark_penalty_removed",
     r"penalty -= 8",
     r"penalty -= 0",
     "ダークパターン罰則削除"),

    ("BTS06_buy_now_threshold_raised",
     r"if total >= 70:\n        return TimingVerdict\.BUY_NOW",
     r"if total >= 85:\n        return TimingVerdict.BUY_NOW",
     "BUY_NOW閾値を70→85に"),

    ("BTS07_wait_threshold_lowered",
     r"if total <= 35:\n        return TimingVerdict\.WAIT",
     r"if total <= 20:\n        return TimingVerdict.WAIT",
     "WAIT閾値を35→20に"),

    ("BTS08_clip_upper_bound_weak",
     r"total = max\(0, min\(100, raw_sum\)\)",
     r"total = max(0, raw_sum)  # 上限クリップ削除",
     "100上限クリップを削除"),

    ("BTS09_volatility_sign_flip",
     r'return TimingSignal\("極めて安定", 10\)',
     r'return TimingSignal("極めて安定", -10)',
     "安定価格の評価を反転"),

    ("BTS10_trend_inverted",
     r'return TimingSignal\("価格下降中 \(待ちが有利\)", -15\)',
     r'return TimingSignal("価格下降中 (待ちが有利)", 15)',
     "下降トレンドの評価を反転"),
]


def apply_mutation(pattern, replacement):
    content = TARGET.read_text()
    if not re.search(pattern, content):
        return False
    new_content = re.sub(pattern, replacement, content, count=1)
    if new_content == content:
        return False
    TARGET.write_text(new_content)
    return True


def run_tests():
    result = subprocess.run(
        [sys.executable, "-m", "pytest",
         "test_buy_timing_scorer.py",
         "--tb=no", "-q", "--no-header", "-x", "--benchmark-disable"],
        cwd=ROOT, capture_output=True, text=True, timeout=120,
    )
    return result.returncode == 0, (result.stdout.strip().split("\n")[-1] if result.stdout else "?")


def main():
    shutil.copy(TARGET, BACKUP)
    try:
        print("=" * 72)
        print("Mutation Testing — buy_timing_scorer.py")
        print("=" * 72)
        print()

        passed, summary = run_tests()
        print(f"Baseline: {summary}")
        if not passed:
            print("⚠ baseline failing")
            return

        print()
        results = []
        for mid, pattern, replacement, desc in MUTATIONS:
            shutil.copy(BACKUP, TARGET)
            if not apply_mutation(pattern, replacement):
                print(f"  ⚠ {mid}: SKIP (pattern not matched)")
                continue
            passed, summary = run_tests()
            killed = not passed
            icon = "✅" if killed else "❌"
            print(f"  {icon} {mid}: {'KILLED' if killed else 'SURVIVED'} — {desc}")
            if not killed:
                print(f"     ⚠ テストすり抜け: {summary}")
            results.append((mid, killed, desc))

        shutil.copy(BACKUP, TARGET)

        print()
        print("=" * 72)
        total = len(results)
        killed = sum(1 for _, k, _ in results if k)
        rate = killed / total * 100 if total else 0
        print(f"結果: {killed}/{total} killed ({rate:.0f}%)")

        survivors = [(mid, desc) for mid, k, desc in results if not k]
        if survivors:
            print("\n🎯 生存ミュータント:")
            for mid, desc in survivors:
                print(f"   - {mid}: {desc}")
    finally:
        if BACKUP.exists():
            shutil.copy(BACKUP, TARGET)
            BACKUP.unlink()


if __name__ == "__main__":
    main()
