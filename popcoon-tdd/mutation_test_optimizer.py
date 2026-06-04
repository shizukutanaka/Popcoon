"""
mutation_test_optimizer.py
alert_optimizer.py に対する組織的ミューテーションテスト。
125行の最適化ロジックに対して14ミュータントで検証。
"""
import subprocess
import sys
import shutil
import re
from pathlib import Path

ROOT = Path("/home/claude/popcoon-tdd")
TARGET = ROOT / "alert_optimizer.py"
BACKUP = ROOT / "alert_optimizer.py.orig"

MUTATIONS = [
    ("OPT01_not_not_removed",
     r"        if isinstance\(inner, AlertCondition\) and inner\.op == \"NOT\":\n            return inner\.children\[0\]\n",
     r"        # NOT NOT 除去を無効化\n",
     "NOT NOT x の除去を無効化"),

    ("OPT02_demorgan_swap_op",
     r'new_op = "OR" if inner\.op == "AND" else "AND"',
     r'new_op = "AND" if inner.op == "AND" else "OR"',
     "ド・モルガン適用時 op反転を忘れる"),

    ("OPT03_empty_and_wrong_default",
     r'        if c\.op == "AND":\n            return ConstantCondition\(True\)',
     r'        if c.op == "AND":\n            return ConstantCondition(False)  # bug',
     "空AND を False に (正: True)"),

    ("OPT04_empty_or_wrong_default",
     r'        if c\.op == "OR":\n            return ConstantCondition\(False\)',
     r'        if c.op == "OR":\n            return ConstantCondition(True)  # bug',
     "空OR を True に (正: False)"),

    ("OPT05_and_false_absorb_removed",
     r"    if op == \"AND\":\n        if any\(always_false\(c\) for c in flat\):\n            return ConstantCondition\(False\)",
     r"    if op == \"AND\":\n        if False and any(always_false(c) for c in flat):\n            return ConstantCondition(False)",
     "AND(..,false,..) の False吸収を無効化"),

    ("OPT06_or_true_absorb_removed",
     r"    else:  # OR\n        if any\(always_true\(c\) for c in flat\):\n            return ConstantCondition\(True\)",
     r"    else:  # OR\n        if False and any(always_true(c) for c in flat):\n            return ConstantCondition(True)",
     "OR(..,true,..) の True吸収を無効化"),

    ("OPT07_complementary_return_wrong",
     r"    if negations:\n        return ConstantCondition\(False if op == \"AND\" else True\)",
     r"    if negations:\n        return ConstantCondition(True if op == \"AND\" else False)",
     "補完ペアの結果を反転"),

    ("OPT08_bounds_contradiction_off",
     r"            if max_below < min_above:\n                return \[ConstantCondition\(False\)\]",
     r"            if max_below > min_above:  # bug: 不等号反転\n                return [ConstantCondition(False)]",
     "PRICE境界矛盾の不等号を反転"),

    ("OPT09_and_keeps_looser_below",
     r"        if below_bounds:\n            result\.append\(min\(below_bounds, key=lambda x: x\[0\]\)\[1\]\)",
     r"        if below_bounds:\n            result.append(max(below_bounds, key=lambda x: x[0])[1])  # bug",
     "AND時の PRICE_BELOW で 緩い方を残す"),

    ("OPT10_or_keeps_stricter_below",
     r"        if below_bounds:\n            result\.append\(max\(below_bounds, key=lambda x: x\[0\]\)\[1\]\)",
     r"        if below_bounds:\n            result.append(min(below_bounds, key=lambda x: x[0])[1])  # bug",
     "OR時の PRICE_BELOW で 厳しい方を残す"),

    ("OPT11_flatten_same_op_removed",
     r"    flat = \[\]\n    for ch in children:\n        if isinstance\(ch, AlertCondition\) and ch\.op == op:\n            flat\.extend\(ch\.children\)\n        else:\n            flat\.append\(ch\)",
     r"    flat = list(children)  # bug: 平坦化削除",
     "AND/OR の平坦化を無効化"),

    ("OPT12_dedup_disabled",
     r"    seen = \{\}\n    for ch in flat:\n        key = _structural_hash\(ch\)\n        if key not in seen:\n            seen\[key\] = ch\n    flat = list\(seen\.values\(\)\)",
     r"    pass  # bug: 重複除去削除",
     "重複除去を削除"),

    ("OPT13_single_child_unwrap_removed",
     r"    if len\(flat\) == 1:\n        return flat\[0\]",
     r"    if False and len(flat) == 1:  # bug\n        return flat[0]",
     "単一子のアンラップを無効化"),

    ("OPT14_always_true_wrong_check",
     r"def always_true\(c\) -> bool:\n    return isinstance\(c, ConstantCondition\) and c\.value is True",
     r"def always_true(c) -> bool:\n    return isinstance(c, ConstantCondition) and c.value is False  # bug",
     "always_true の判定を反転"),
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
        [sys.executable, "-m", "pytest", "test_alert_optimizer.py", "test_integration.py",
         "--tb=no", "-q", "--no-header", "-x", "--benchmark-disable"],
        cwd=ROOT, capture_output=True, text=True, timeout=120,
    )
    passed = result.returncode == 0
    summary = result.stdout.strip().split("\n")[-1] if result.stdout else "no output"
    return passed, summary


def main():
    shutil.copy(TARGET, BACKUP)
    try:
        print("=" * 72)
        print("Mutation Testing — alert_optimizer.py")
        print("=" * 72)
        print()

        passed, summary = run_tests()
        print(f"Baseline: {summary}")
        if not passed:
            print("⚠ baseline failing, aborting")
            return

        print()
        results = []
        for mid, pattern, replacement, desc in MUTATIONS:
            shutil.copy(BACKUP, TARGET)
            applied = apply_mutation(pattern, replacement)
            if not applied:
                print(f"  ⚠ {mid}: SKIP (pattern not matched)")
                continue

            passed, summary = run_tests()
            killed = not passed
            icon = "✅" if killed else "❌"
            status = "KILLED" if killed else "SURVIVED"
            print(f"  {icon} {mid}: {status}")
            print(f"     {desc}")
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
            print("\n🎯 生存ミュータント (テスト不足):")
            for mid, desc in survivors:
                print(f"   - {mid}: {desc}")
        else:
            print("\n✅ 全ミュータント検出")
    finally:
        if BACKUP.exists():
            shutil.copy(BACKUP, TARGET)
            BACKUP.unlink()


if __name__ == "__main__":
    main()
