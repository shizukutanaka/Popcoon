# INSTRUCTIONS-SONNET.md — 機械的タスクのレシピ集 (C1–C6)

対象: Claude Sonnet クラスのセッション。機械的・検証容易でそのまま着手可能なタスク集。
各レシピは**実在の直近コミットをテンプレート**として参照する — 迷ったらその diff を読め。
番号は `docs/ASSESSMENT-2026-07.md` の改善案テーブルに対応。

**してはいけないこと (再掲)**: golden/差分テスト期待値の安易な書換え・`main` への push・
未配線のデッドコード追加・出典なしの外部仕様値変更。**着手前に `CLAUDE.md` の検証コマンドで
基準線を確認。**

---

## C1. ダークパターン regex 追加

**模範例**: commit `2839cca` (SCARCITY カウンタを 個/セット/台・あと へ拡張)。

**5 ステップ (この順)**:
1. `popcoon-tdd/proto_darkpattern_signals.py` にパターン追加 (誤爆ガードを regex に埋める)
2. `popcoon-tdd/test_proto_darkpattern_signals.py` に**正ケース+負ケース** (誤検出しない証明) 追加
   → `python3 -m pytest test_proto_darkpattern_signals.py -q` green
3. `app/src/main/.../feature/darkpattern/DarkPatternTextDetector.kt` に**厳密一致**で移植
4. `popcoon-tdd/kotlin_parity/ParityHarness.kt` の `texts` リストに `TX(...)` ケース追加
   (正・負両方)
5. `bash popcoon-tdd/kotlin_parity/run.sh` → "matched" 数が増え "0 mismatched" を確認 +
   `app/src/test/.../DarkPatternTextDetectorTest.kt` に golden 追加

**原則**: 正規マーケティング (数量限定/ベストセラー/人気No.1 等) と区別できない語は**追加しない**。
明確に操作的な語のみ。

---

## C2. 属性 recall 追加 (ProductMatcher)

**模範例**: 色追加 `6c2e57e` / 内容量・重量 `09bfc1f`。

**原則**:
- **保守的正準写像**: 新シェード/単位は最寄りの既存正準値へ寄せる (過剰分離で同一商品を
  誤って別 SKU にしない)。真に独立なものだけ新正準値 (例: カーキ→KHAKI)
- **誤爆ガード必須**: 大小・複合語・別ドメインの衝突を負ケースで固定 (例: 素の g は小文字のみ=
  「5G」対策、「ミリ」単体除外=「5ミリ=長さ」対策)
- 減点は「両タイトルから一意に取れて食い違う場合のみ」

**手順**: (内容量など oracle 化されたものは) proto→test→Kotlin→`ProductMatcherCheck.kt`→
`run_matcher.sh`。色のように Kotlin 専用ロジックは Kotlin+`ProductMatcherCheck.kt`+`run_matcher.sh`。
**既存 40+ ケース無回帰が合格条件**。`run_matcher.sh` は実 Kotlin 実行なので誤前提を捕捉する
(色追加時に実際に私の誤前提を検出した — ハーネスを信じて期待値を直せ)。

---

## C3. parity ケース増強

各ハーネスとチェックファイルの対応:

| スクリプト | チェックファイル | 対象 |
|---|---|---|
| `run.sh` | `ParityHarness.kt` | darkpattern/cart/customs/ethics/prediction/calendar 等 |
| `run_matcher.sh` | `matching/ProductMatcherCheck.kt` | 名寄せ |
| `run_points.sh` | `points/PointSimulatorCheck.kt` | ポイント |
| `run_alerts.sh` | (run_alerts 内) | 価格アラート/デバウンス |
| `run_trie.sh` / `run_currency.sh` / `run_jan.sh` 等 | 各 `*/…Check.kt` | 個別 |

境界ケース (全角/半角数字・全角空白 U+3000・空文字・巨大値・小数) を追加。
`bash popcoon-tdd/kotlin_parity/run_all.sh` で全 13 ハーネス green 維持が合格条件。

---

## C4. backend テスト追加

**模範例**: `backend/test/worker.test.ts` の実ハンドラー呼び出しパターン:
```ts
import { env, createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
const ctx = createExecutionContext();
const res = await worker.fetch(req, env, ctx);
await waitOnExecutionContext(ctx);
```
未カバー経路 (異常系ヘッダ・CORS preflight・巨大 payload 境界値・admin ゲート) を追加。
ratelimit binding は miniflare で undefined になり KV フォールバック経路を通る点に注意
(理由は worker.test.ts 冒頭コメント)。検証: `cd backend && npx tsc --noEmit && npx vitest run`
(現 70 tests から増える)。

---

## C5. docs 保守

実装変更時に同期する対象: `README.md` / `ARCHITECTURE.md` / `CHANGELOG.md` /
`docs/RESEARCH-2026-07.md` / `docs/ASSESSMENT-2026-07.md`。

**「数値は必ず実測」原則**: テスト件数・行数・ロケールキー数・parity matched 数は
記載前に実コマンドで測る (推測禁止)。基準線更新時は `CLAUDE.md` の表も同時更新。

---

## C6. ViewModel テスト追加

**模範例**: `app/src/test/.../watchlist/WatchlistViewModelTest.kt` の
`FakeWatchlistDao` / `FakeUserPreferences` / `FakeWidgetRefresher` パターン。

Context/具象 DataStore を直接注入せず、`IUserPreferences` / `IWidgetRefresher` のような
インタフェース seam に Fake を差す。DAO 例外時の try/catch 回帰 (throwOn パターン) も
テストできる。plain JVM ユニットテスト (Robolectric なし) なので Android 依存の少ない
ViewModel を優先。検証: brace/paren バランス + 既存 Fake パターン踏襲 (実行は CI 待ち)。
