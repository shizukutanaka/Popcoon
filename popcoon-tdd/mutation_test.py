"""
mutation_test.py
ミューテーションテスト — バグを意図的に注入しテストが検出するか検証。

目的: テストスイートの「実際の検出能力」を測定する。
    カバレッジ100%でもバグを見逃すことは多い。
    ミューテーションテストで「意味のあるテスト」かを検証。

結果:
    Killed   = バグ注入がテストで検出された (良い)
    Survived = バグ注入がテストをすり抜けた (危険)

survival率 0% が理想、20%以下で合格。
"""
import subprocess
import sys
import shutil
import re
import os
from pathlib import Path

ROOT = Path("/home/claude/popcoon-tdd")
CORE = ROOT / "popcoon_core.py"
BACKUP = ROOT / "popcoon_core.py.orig"

# 注入するミューテーション: (識別子, find_pattern, replace_pattern, description)
MUTATIONS = [
    ("MU01_threshold_boundary",
     r"TAX_EXEMPT_THRESHOLD = 16_666",
     r"TAX_EXEMPT_THRESHOLD = 16_667",  # off-by-one
     "関税免税境界値を16,666→16,667に変更"),

    ("MU02_shoe_duty_rate",
     r'"靴": 0\.30,',
     r'"靴": 0.25,',
     "靴の関税率を30%→25%に変更"),

    ("MU03_confidence_threshold",
     r"Confidence.HIGH if len\(records\) >= 90",
     r"Confidence.HIGH if len(records) >= 100",
     "HIGH信頼度の閾値を90→100に変更"),

    ("MU04_charm_pricing_range",
     r"if 80 <= last_two <= 99:",
     r"if 85 <= last_two <= 99:",  # 80-84 を見逃す
     "端数価格検出範囲を狭める"),

    ("MU05_always_discount_threshold",
     r"if below / len\(history\) > 0\.90:",
     r"if below / len(history) > 0.95:",
     "常設セール閾値を90%→95%に変更"),

    ("MU06_eco_cert_bonus",
     r"co2_score = min\(100, co2_score \+ 10\)",
     r"co2_score = min(100, co2_score + 5)",
     "エコ認証ボーナスを+10→+5に削減"),

    ("MU07_trie_size_not_increment",
     r"self\._size \+= 1",
     r"pass  # 意図的バグ",
     "Trie.size() が 0 のまま"),

    ("MU08_total_price_signflip",
     r"return self\.real_price \+ self\.shipping_fee - self\.points_back",
     r"return self.real_price + self.shipping_fee + self.points_back",
     "total_price でポイント差引を加算に変更"),

    ("MU09_tco_monthly_divisor",
     r"return r\.tco_per_month\b",
     r"return r.tco_per_month",  # 変更なし (同値ミュータント、検出されないべき)
     "[同値ミュータント] 変更なし"),
]


def apply_mutation(pattern: str, replacement: str) -> bool:
    content = CORE.read_text()
    if not re.search(pattern, content):
        return False
    new_content = re.sub(pattern, replacement, content, count=1)
    CORE.write_text(new_content)
    return True


def restore():
    shutil.copy(BACKUP, CORE)


def run_tests() -> tuple[bool, str]:
    """テスト全実行。成功なら (True, summary) を返す"""
    result = subprocess.run(
        [sys.executable, "-m", "pytest", "test_popcoon_core.py",
         "--tb=no", "-q", "--no-header", "-x",
         "--benchmark-disable"],
        cwd=ROOT, capture_output=True, text=True, timeout=120,
    )
    passed = result.returncode == 0
    summary = result.stdout.strip().split("\n")[-1] if result.stdout else "no output"
    return passed, summary


def main():
    # バックアップ作成
    shutil.copy(CORE, BACKUP)

    print("=" * 72)
    print("Mutation Testing — Popcoon Core")
    print("=" * 72)
    print()

    # Baseline: ミューテーションなしでテスト成功を確認
    print("Baseline (no mutation)...", end=" ", flush=True)
    passed, summary = run_tests()
    print(f"{summary}")
    if not passed:
        print("⚠ baseline test failing, mutation testing invalid.")
        return

    print()
    print("Applying mutations...")
    print()

    results = []
    for mid, pattern, replacement, desc in MUTATIONS:
        # 復元してから注入
        restore()
        applied = apply_mutation(pattern, replacement)
        if not applied:
            print(f"  {mid}: [SKIP] pattern not found")
            continue

        passed, summary = run_tests()
        # バグ注入でテスト失敗 = Killed (良い)
        # 通過 = Survived (テストが検出できていない)
        killed = not passed
        icon = "✅" if killed else "❌"
        status = "KILLED" if killed else "SURVIVED"
        print(f"  {icon} {mid}: {status}")
        print(f"     {desc}")
        if not killed:
            print(f"     ⚠ テストがバグを見逃した: {summary}")
        results.append((mid, killed, desc))

    # 最後に復元
    restore()

    # サマリ
    print()
    print("=" * 72)
    total = len(results)
    killed = sum(1 for _, k, _ in results if k)
    survived = total - killed
    kill_rate = killed / total * 100 if total else 0
    print(f"結果: {killed}/{total} killed ({kill_rate:.0f}%), {survived} survived")
    print()
    if survived > 0:
        print("🎯 要改善: 以下のミューテーションがすり抜けた")
        for mid, k, desc in results:
            if not k:
                print(f"   - {mid}: {desc}")
        print()
        print("→ これらを検出する追加テストを書くべき。")
    else:
        print("✅ 全ミューテーション検出: テストスイートは高品質")


if __name__ == "__main__":
    main()
