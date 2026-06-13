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
# => PARITY: 39 matched, 0 mismatched  (全一致なら exit 0)
```

## カバレッジ (移植済み純関数 6 種すべて実行検証)

| 関数 | 状態 | 備考 |
|------|------|------|
| `CustomsSimulator.simulate` | ✅ 実行検証 (11 ケース) | Tier 9 の verdict バグ修正がコンパイル&実行で確認済み |
| `EcoEthicsScorer.score` | ✅ 実行検証 (7 ケース) | 定数・式・丸め・日本語文字列まで一致 |
| `DarkPatternDetector.detect` | ✅ 実行検証 (7 シナリオ) | 4 種の警告 (常設/参考価格/値上げ/端数) すべて発火し一致 |
| `PricePredictionEngine.predict` | ✅ 実行検証 (7 シナリオ) | Holt/IQR/buy-prob/confidence。margin/seasonal は Kotlin 拡張なので比較対象外 |
| `BuyTimingScorer.score` | ✅ 実行検証 (7 シナリオ) | 看板機能。`today=null` で Python 同等経路を照合 (67/72/55/62/67/42/null) |

履歴依存関数は `PriceRecord` (Product.kt) を要するが、`@Serializable` の**コンパイラ plugin は
不要**だった: plugin 不在でもアノテーションは無害で、ロジックは `.serializer()` を呼ばないため、
**本物のソースが serialization ランタイム jar のみでコンパイルできる** (スタブ不要)。

## 嵌りどころ

- **JVM stdout が ASCII**: `stdout.encoding=ANSI_X3.4-1968` だと日本語が `?` に化け、Python が
  壊れた入力で再計算して偽 mismatch を出す → `-Dstdout.encoding=UTF-8` 必須 (run.sh で設定済み)。

## 限界

これは純粋ロジックを単体でコンパイル/実行するもので、**full app (Compose/Room/DI/Hilt) の
コンパイルは検証しない**。それは CI (`.github/workflows/`) でしか確認できない。
本ハーネスは「移植ロジックのパリティ」専用の、SDK 不要な検証経路。
