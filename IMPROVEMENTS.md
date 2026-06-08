# Popcoon 改善メモ (deep research)

コードベース全層 (build / data・network / feature・domain / Python TDD parity / UI・Compose /
CI) を調査した結果と、適用した改善・今後のバックログ。

## 適用済み (Tier 1: build + correctness + safety)

| # | 内容 | 主な変更 |
|---|------|----------|
| 1 | **コンパイルブロッカー修正**: `Pop_TealDark` がどこにも定義されず参照されていた (現状ビルド不能) | `ui/theme/Theme.kt` |
| 2 | **全 Ktor クライアントに HttpTimeout 追加** (デフォルト 100s ハングを防止) | network/* , repository/BackendClient, ai/BuyingAdvisor, crash/PrivacyCrashReporter |
| 3 | **DB 破壊的マイグレーションを debug 限定化** (release でのユーザーデータ消失防止) | `di/DatabaseModule.kt` |
| 4 | **robots.txt 遵守を実装** (doc は「尊重」と書きつつ未実装だった) + 純関数パーサと単体テスト | `network/FallbackScraper.kt`, `network/RobotsTxt.kt` |
| 5 | **EcoEthicsScorer の Kotlin↔Python 乖離を解消** (docstring は「完全一致」と主張も別式だった)。Python oracle に一致させ、絶対値パリティテストで固定 | `feature/ethics/EcoEthicsScorer.kt` + test |
| 6 | **PopcoonLogger の秘密情報リダクション強化** (旧 regex はマルチパラメータ URL で API キーを伏せ損ねる) | `core/PopcoonLogger.kt` + test |
| 7 | **ハードコード日本語 UI 文字列をリソース化** (en/ko/zh で日本語露出) | `ui/components/*`, `res/values*/strings.xml` |

## 適用済み (Tier 2: 競合調査ベースの機能・基盤)

GitHub 上の同種 OSS 価格追跡アプリ (CamelCamelCamel, Keepa, ShopSense, Pricewise,
jeevandhakal/price_comparison, edent/Amazon-Wishlist-Pricedrop-Alert 等) を調査し、
Popcoon に欠けていた最も普遍的な機能と、その検証基盤を実装した。

| # | 内容 | 主な変更 |
|---|------|----------|
| 8 | **希望価格 (target price) アラート**: 競合が普遍的に持つ「指定価格まで下がったら通知」。従来は相対値下がり率のみで、予算までの緩やかな下落を取りこぼしていた。目標到達は率の閾値を無視し最優先で通知。純関数 `PriceAlertEvaluator` (18 テスト) + Room v1→v2 マイグレーション + ウォッチリスト UI (チップ/ダイアログ) | `feature/notification/PriceAlertEvaluator.kt`, `data/db/PopcoonDatabase.kt`, `di/DatabaseModule.kt`, `worker/PriceSyncWorker.kt`, `ui/components/TargetPriceDialog.kt`, `ui/screens/watchlist/*`, `res/values*/strings.xml` |
| 9 | **CI ワークフロー新設**: TDD 重視の設計にも関わらず CI が存在せず、ローカル Android SDK も無いため Kotlin が一度もコンパイル検証されていなかった。detekt/lint/単体テスト/assemble + Python オラクルを実行。App の `workflows` 権限制約のため `ci/` にテンプレートとして配置 (管理者が 1 行で有効化) | `ci/android.yml`, `ci/README.md` |
| 10 | **潜在コンパイルエラー修正**: `PriceSyncWorker` が `CurrencyFormatter` を import せず参照していた (HEAD でも壊れていた) | `worker/PriceSyncWorker.kt` |

## 検証
- Python TDD: `cd popcoon-tdd && python3 -m pytest -q` (290 passed, 1 skipped)。
- Kotlin: ローカルに Android SDK が無いため inspection で担保。`ci/android.yml` を
  `.github/workflows/` に配置すると lint / detekt / `testDebugUnitTest` / assemble が
  自動検証する (有効化手順は `ci/README.md`)。

## 今後のバックログ (未適用)

### 信頼性・品質 (Tier 2)
- ProductRepository に API レート制限 / 指数バックオフ / サーキットブレーカ。
- FallbackScraper の JSON-LD 抽出を regex から正規 JSON パーサへ (数値 price・エスケープ対応)。
- 検索の in-flight キャンセル (古いクエリ結果の上書き防止) と SearchScreen のリトライ UI。
- AdviceCache の TTL / 退避テスト、`put` の同期化 (上限超過の競合)。
- ProductDetailViewModel / WatchlistViewModel / SettingsViewModel の単体テスト追加。
- CI: ~~ワークフロー新設~~ (#9 で実装、要有効化) → 次は detekt / Kover カバレッジを
  マージゲート化、baseline profile 検証。
- a11y 文字列: ~~`VerdictBadge.kt` の i18n~~ (実装済み) → 残りは `AccessibilityExt.kt`
  (Context 引数が必要な非 Composable のため要設計)。

### 競合調査バックログ (同種 OSS 価格追跡アプリ由来、未適用)
GitHub 調査で確認した、競合にあり Popcoon に未実装の機能。インパクト順:
- **ウォッチリストの整理**: 優先度/カテゴリ/店舗でのソート・タグ付け・並べ替え (Karma 等)。
- **クーポン/プロモコード集約**と決済前の自動適用 (Honey, Karma の中核機能)。
- **URL 貼り付けで追加**: 共有インテント (`ACTION_SEND`) を受けて商品 URL から登録。
- **在庫アラート**: 再入荷/在庫切れ通知 (現状は価格のみ)。
- **「追加時からの変動」表示**: ウォッチ追加時価格を基準に下落幅を可視化 (CamelCamelCamel)。
- **値下がりフィード**: ウォッチ外の急落商品を一覧する発見導線。
- **多通貨対応**: 越境購入 (CustomsSimulator) と整合する通貨換算表示。

### アルゴリズム (Tier 3)
- 価格予測を Holt 線形から Holt-Winters (季節性) へ。
- Kotlin↔Python 差分/契約テスト基盤 (CustomsSimulator / TCOCalculator / PricePredictionEngine /
  BuyTimingScorer の出力一致を CI で保証)。
- Kotlin 側ミューテーションテスト (Python は 100% kill 達成済み)。

### 設計判断 (要検討)
- TCOCalculator の残価が長期で 0% になる (現実は 5-15%) — 下限を設けるか要件確認。
- CustomsSimulator の関税丸めは truncate (日本の実務は切り上げが一般的) — 仕様確認。
