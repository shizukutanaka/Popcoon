# Kotlin ↔ Python パリティ実行ハーネス

プロジェクトの看板は "Python parity"（Kotlin 移植が `popcoon_core` の Python リファレンスと
一致）。だが従来それを**強制する機構が無かった** — `test_differential.py` は Python 同士の
比較であり、実際 `CustomsSimulator` は verdict 分岐順が乖離し、Kotlin の単体テストがその
バグを固定していた（IMPROVEMENTS.md Tier 9）。

このハーネスは parity を**文書上の主張から実行可能な検証へ**変える。**Android SDK は不要**。

## 仕組み

```
run.sh
 ├─ Gradle 同梱の kotlin-compiler-embeddable で
 │    本物の Kotlin 実装 (CustomsSimulator, EcoEthicsScorer) + ParityHarness.kt をコンパイル
 ├─ JVM で実行 → 各入力に対する Kotlin の出力を TSV で印字
 └─ compare_oracle.py が同じ入力で Python オラクル (popcoon_core) を再計算し照合
```

入力は Kotlin の出力に含まれ、Python がそれを読んで再計算するため、
**fixture の drift が原理的に起き得ない**生きた等価性チェックになる。

## 実行

```bash
# 前提: 一度 ./gradlew --version を実行して wrapper distribution を取得済みであること
#       (kotlin-compiler-embeddable jar を ~/.gradle から探す)
bash popcoon-tdd/kotlin_parity/run.sh
# => PARITY: 18 matched, 0 mismatched  (全一致なら exit 0)
```

## カバレッジと拡張

| 関数 | 状態 | 備考 |
|------|------|------|
| `CustomsSimulator.simulate` | ✅ 実行検証 (11 ケース) | Tier 9 の verdict バグ修正がコンパイル&実行で確認済み |
| `EcoEthicsScorer.score` | ✅ 実行検証 (7 ケース) | 定数・式・丸め・日本語文字列まで一致 |
| `BuyTimingScorer` / `PricePredictionEngine` / `DarkPatternDetector` | ⏳ 未対応 | `PriceRecord` (Product.kt, kotlinx-serialization plugin 依存) のコンパイルが必要。`-Xplugin=kotlinx-serialization-compiler-plugin` + serialization ランタイムを classpath に追加すれば拡張可能。あるいは履歴依存関数用に最小 `PriceRecord` スタブ (realPrice/recordedAt) を用意する |

## なぜスカラー関数から始めるか

`CustomsSimulator` と `EcoEthicsScorer` は kotlin stdlib のみに依存（Android/Room/serialization
非依存）→ 追加 classpath ゼロでコンパイルできる。最小リスクで「実行可能パリティ」の足場を作り、
履歴依存関数は段階的に追加する方針。
