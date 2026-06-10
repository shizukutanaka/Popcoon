# Popcoon 改善メモ (deep research)

コードベース全層 (build / data・network / feature・domain / Python TDD parity / UI・Compose /
CI) を調査した結果と、適用した改善・今後のバックログ。

## 製品分析 (Tier 7: 長所・短所・不足機能の洗い出しと実装)

プロダクトとしての強み・弱み・不足機能を棚卸しし、価値が高く自己完結する
ギャップを実装した。

### 長所 (差別化の核心)
- **買い時判定の独自性**: `BuyTimingScorer` (ATL近接・トレンド・変動率・季節性) +
  `PricePredictionEngine` (Holt線形 + Conformal 区間 + 季節分解) は競合14アプリ非搭載。
- **ダークパターン暴露**: 価格系 + テキスト5カテゴリ検出 + `ReviewTrustScorer` (統計的サクラ検出)。
- **長期コスト可視化**: `TCOCalculator` (消耗品・電力)、`BundlePackDetector` (実質単価)、
  `PointSimulator` (ポイント還元後実質価格)。
- **プライバシー優先**: オンデバイス完結、レビュー本文を端末外に送らない。
- **Python TDD オラクル**: アルゴリズムは Python 正本と差分テストでパリティ保証 (290 tests)。
- **4ロケール対応** (ja/en/ko/zh-rCN)、ウォッチリスト + 目標価格アラート + ウィジェット + バーコード。

### 短所・不足 (今回修正)
| # | 分類 | 内容 | 対応 |
|---|------|------|------|
| 47 | **不足機能** | `EcoEthicsScorer` (CO2・労働権利スコア、Python パリティ・テスト済み) が UI から一切参照されず **死蔵** | 商品詳細に `EthicsCard` を新設し配線。原産国判明時のみ算出 (不明時は無意味な定数のため非表示)。スコアロジックは不変、表示のみ追加 |
| 48 | **不足機能** | ホーム画面ウィジェットの判定が全件 `"NEUTRAL"` 固定で無意味 | `WidgetVerdict` 純関数を新設 (目標到達/追加時比±5% で BUY_NOW・WAIT・NEUTRAL を導出) + 単体テスト。完全な履歴を要する詳細画面スコアラとは別の軽量判定として明示 |
| 49 | **テスト基盤** | `testOptions` に `useJUnitPlatform()` 不在 → Kotest spec は JUnit4 ランナーで **1件も発見されず**、Kotlin 単体テスト群 (200+) が実質未実行だった | `unitTests.all { it.useJUnitPlatform() }` を追加。これで `ci/android.yml` の単体テストジョブが初めて意味を持つ |
| 50 | **CI** | `gradlew` の実行ビットが欠落 (100644) | `100755` に修正。`./gradlew` がフレッシュチェックアウトで動作 |

### 確認した非ギャップ (誤検知防止メモ)
- **目標価格設定 UI**: `TargetPriceDialog` / `TargetPriceChip` / VM 配線が既に存在 — 不足ではない。
- **CI ワークフロー本体**: `ci/android.yml` テンプレートが既に存在 (GitHub App の `workflows` 権限欠如で
  `.github/workflows/` へ push 不可、`ci/README.md` 参照)。リポジトリ管理者が `git mv` で有効化する。
- **`PointSimulator` / `PricePredictionEngine` の整数除算疑い**: いずれも Double 演算 (Kotlin の型昇格) で
  精度損失なし — バグではない。

### 追加実装 (Tier 7 後続)
| # | 分類 | 内容 | 対応 |
|---|------|------|------|
| 51 | **不足機能** | `SaleCalendar` (テスト済み) が検索の当日バナーでしか露出せず、今後のセールを一覧できない (docstring が「Pricey 相当の主要差別化」と明記) | `SaleCalendar.upcomingSales` 純関数+テストを追加し、`SaleCalendarScreen` (開催中/今後の大型セール) を新設。検索トップバーのカレンダーアイコン+当日バナータップで遷移。4ロケール対応 |

### 残課題 (未着手)
- **`CustomsSimulator`**: 越境関税シミュレータも UI 未配線だが、国内中心の用途では優先度低 (要・原産国/輸入判定入力)。
- **CI 緑化の確認**: 上記 #49 で初めて Kotest が走るため、未実行だった spec に潜在失敗が無いか CI 有効化後に要確認
  (ローカルは Android SDK 不在で検証不可)。

## 適用済み (Tier 6: 並行性・セキュリティ・バグの第4回監査)

データ/キャッシュ・ViewModel・Share・課金 の 4 カテゴリを徹底監査。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 40 | バグ/コンパイル | `SettingsViewModel.launchBillingFlow(activity, offer)` は存在しないメソッド (コンパイルエラー) → `launchPurchase(activity, offer)` に修正 | HIGH |
| 41 | 並行性 | `BillingManager.queryOffers()` の `suspendCancellableCoroutine` で `isActive` ガード不在 → コルーチンキャンセル後に `resume()` が呼ばれ `IllegalStateException` → ガード追加 | HIGH |
| 42 | UX/性能 | `SearchViewModel` が進行中の検索をキャンセルせず新クエリを最大 2 秒待たせる (後行クエリがキュー待ち) → `searchJob?.cancel()` + 新 `Job` で即時切替 | MED |
| 43 | セキュリティ | `UrlClassifier.extractUrl` の `[^\s]+` が 2048 文字超の URL を制限なく返す → `\S{1,2048}` で上限化 | MED |
| 44 | 正当性 | `MainActivity.handleIntent` が `extractUrl` の null 時に raw テキストを `classify` に渡す (URL なし文字列で不定動作) → `?: return` に修正 | MED |
| 45 | 並行性 | `ProductNavCache.put()` が `ConcurrentHashMap` + check-then-act でスレッド非安全 (2スレッドが同時に上限チェックして不整合) → `LinkedHashMap` + `@Synchronized` で原子化、挿入順 FIFO を保証 | MED |
| 46 | 並行性 | `AdviceCache.put()` が `@Synchronized` でない `evictIfNeeded()` を外から呼ぶ不整合パターン → `put()`/`get()` を `@Synchronized` 化、`evictIfNeeded` をインライン化、`ConcurrentHashMap` → `HashMap` に統一 | LOW |

### 監査で確認した非バグ (誤検知防止メモ)
- `PricePredictionEngine.percentile = cleaned.count { it >= current }`: `>= current` は正しい。現価格を下回る件数が多いほど "安い" を意味するが、`>= current` は "現価格以上の履歴件数 / 総数" = 買い時確率 (高 = 安い) と同義 — **反転ではなく正しい計算**。
- `RobotsTxt.endAnchored = anchored && !core.endsWith("*")`: `*$` パターンの場合 `endAnchored=false` になるが、`*` が可変長マッチするので末尾アンカーと同値 — **バグではない**。
- `Trie`: `ReentrantReadWriteLock` で insert (write) / suggest (read) を保護済み — **スレッドセーフ**。

## 適用済み (Tier 5: ライフサイクル・テスト品質・UI一貫性・性能の第3回監査)

ライフサイクル/購読・テスト品質・UI アイコン一貫性・再描画性能の 4 観点で再監査。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 33 | ライフサイクル | `collectAsState` → `collectAsStateWithLifecycle` (6画面・12箇所): バックグラウンド時の購読停止。`lifecycle-runtime-compose 2.8.7` 依存追加 + 全6ファイル移行 | MED |
| 34 | 並行性/correctness | `ProductDetailViewModel` AI 助言上書きが check-then-set 競合 → `_state.update { cur -> if (cur is Loaded && cur.product.key == product.key) ... }` でアトミック化 | MED |
| 35 | 性能/Compose | `PriceChart` の `sortedBy`/`min`/`max` がリコンポジション毎に再計算 → `remember(records)` / `remember(sorted)` で key 変化時のみ再計算 | MED |
| 36 | テスト品質 | `ReviewPrompterLogicTest` がテスト内でロジックを再実装 (回帰検出不能) → `ReviewPrompter.shouldRequestNow()` companion 純関数を抽出してテストが本番呼び出しに | MED |
| 37 | テスト品質 | `NotificationLogicTest` が通知 ID・テキスト・URI をテスト内で再実装 → `LocalNotificationManager.{notificationId, priceAlertText, deepLinkUri}()` companion 純関数を抽出 | MED |
| 38 | テスト品質 | `PriceSyncWorkerLogicTest` が値下がり率を直接計算してテスト — `PriceAlertEvaluator.evaluate()` を直接呼ぶテストに書き換え + `WORK_NAME` を `internal` 公開 | LOW |
| 39 | UI一貫性 | `SearchSuggestions`/`OfflineBanner`/`ProductDetailScreen`/`SearchScreen`/`SettingsScreen` が `Icons.Default.*` を直参照 (AppIcons 方針違反) → 全5ファイルを `AppIcons` 経由に統一 | LOW |

### 今後のバックログ (round 3 で確認、未適用)
- `HapticFeedbackTest` / `BillingManagerTest`: 定数のみ検証でロジック保護なし。
  `HapticFeedback` の vibration効果定数は Android API 由来で変更困難。
  `BillingManager` のSKU/価格はサービス仕様変更時の意図的変更のため現状維持で可。
- `AccessibilityExt.kt` の `verdictA11yLabel` / `darkPatternA11yLabel`: 現状は
  Kotlin 文字列定数で多言語非対応。非 Composable 関数のため `Context.getString()` を
  呼ぶ設計変更が必要 (シグネチャ破壊あり) — 要設計検討。

## 適用済み (Tier 4: 並行性・性能・テスト品質の徹底監査)

ビルド/マニフェスト/セキュリティ設定・並行性/ライフサイクル/性能・テスト網羅の
3 観点で再監査。設定 (network_security_config / backup / FileProvider / manifest) は
全て健全。並行性・性能で確認した問題を修正。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 25 | 性能 | `WatchlistViewModel.smartCart` が総当たり最適化 (最大 200k) をメイン/即時 dispatcher で実行 → `flowOn(Dispatchers.Default)` | HIGH |
| 26 | 並行性 | `PriceSyncWorker` が逐次フェッチ + 常に `success` (バックオフ死蔵)。Semaphore(8) 並列化 + 全件失敗時のみ `retry` | HIGH |
| 27 | 並行性 | `WidgetUpdater.pendingJob` が Main/Worker から無同期 check-then-act 競合 → lock で atomic 化 | HIGH |
| 28 | 並行性/leak | `BackendClient.postPriceAsync` が検索結果ごとに無制限 launch (≈90 並行 POST、応答未消費) → `postPricesAsync(List)` で 1 コルーチン順次送信 + `bodyAsText` で接続解放 | HIGH |
| 29 | 性能 | `SearchViewModel` がグループごとに価格履歴を逐次取得 → `async`/`awaitAll` で並列化 | MED |
| 30 | 性能 | `FallbackScraper` が Regex を呼び出しごとに再コンパイル → companion 定数 + キー別キャッシュ。レート制限ゲートを `compute` で atomic 化 | MED |
| 31 | バグ/テスト | `PopcoonWidget` の楽天セール分岐に到達不能な day=5。純関数 `PopcoonWidgetLogic` に抽出して修正、テストを本番呼び出しに | LOW |
| 32 | テスト | `Product`/`Platform` の派生プロパティと `fromId` フォールバック契約 (未知→AMAZON) を `ProductTest` で固定 | — |

### 今後のバックログ (round 2 で確認、未適用)
- `collectAsState` → `collectAsStateWithLifecycle` (6 画面 12 箇所): バックグラウンド時の
  購読停止。要 `androidx.lifecycle:lifecycle-runtime-compose` 依存追加 (CI で要検証)。
- 弱いテスト (本番ロジックをテスト内に再実装し回帰検出不能): `PriceSyncWorkerLogicTest`,
  `NotificationLogicTest`, `ReviewPrompterLogicTest`, `HapticFeedbackTest`, `BillingManagerTest`
  → 純関数を本番側に抽出して本番呼び出しに (Widget は #31 で対応済み)。
- AppIcons 集約方針の徹底 (画面の直 `Icons.Default.*` を `AppIcons` 経由に)。
- `ProductDetailViewModel` の AI 助言上書きを `_state.update{}` + productKey 一致確認に。

## 適用済み (Tier 3: カテゴリ別徹底監査)

プロダクトを 5 カテゴリ (データ&永続化 / 価格アルゴリズム / 消費者保護 / UI・Compose /
ウォッチリスト・カート・課金・バックグラウンド) に分割し、各層を実コードまで精査。
CI も Android SDK も無く Kotlin が一度もコンパイルされていないため、**コンパイル不能
バグが多数潜伏**していた。確認済みのものを全て修正 (各々テスト付きまたは inspection 検証)。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 13 | データ | `AmazonPaApiClient.SearchItemsRequest` の primary/secondary コンストラクタが同一 JVM シグネチャ → conflicting overloads (コンパイル不能) | HIGH |
| 14 | データ | `PriceRecord` が `Instant` を `@Serializable` するもシリアライザ不在 → コンパイル不能。`InstantIso8601Serializer` 追加 + 往復テスト | HIGH |
| 15 | DI | Hilt 二重バインディング **7 件** (`@Inject` + `@Provides`): AdviceCache, BuyingAdvisor, BackendClient, PriceHistoryCsvExporter, StartupTracker, ReviewPrompter, ConnectivityObserver → 全て Dagger コンパイル不能。冗長 `@Provides` を削除 | HIGH |
| 16 | データ | `YahooClient` が `premiumPrice` (会員割引価格) を list price に使い割引表示が反転 → `defaultPrice` に修正 | MED |
| 17 | アルゴリズム | `SaleCalendar.nextMajorSale` が当年のみ生成 → 12/7–31 に null。翌年分を併合 + 年境界回帰テスト | MED |
| 18 | 課金 | `AffiliateUrlBuilder` 楽天リンクが商品 URL を未エンコードで `pc=` に連結 → リンク破損 (収益逸失)。`Uri.encode` | MED |
| 19 | クラッシュ | `PrivacyCrashReporter` が ① クラッシュ時 fire-and-forget で送信未達 ② 保存形式と送信形式が不一致。永続化→次回起動送信パターンに修正 | MED |
| 20 | UI/i18n | ProductDetail 価格カード・Watchlist 空状態・Barcode エラー 4 箇所の日本語直書きをリソース化 (en/ko/zh 対応) | MED |
| 21 | UI/compose | `SearchSuggestions` の LazyColumn に安定キー付与 | MED |
| 22 | セキュリティ | CSV エクスポートの数式インジェクション対策 (`=+-@` 始まりに `'` 前置) + テスト | LOW |
| 23 | プライバシー | `PopcoonLogger` が Throwable を未サニタイズで Logcat 出力 → サニタイズ連結に修正 | LOW |
| 24 | UI/a11y | 検索画面のお気に入りボタンが「保存」と誤読み上げ → `nav_watchlist` | LOW |

消費者保護カテゴリ (darkpattern/review/ethics) は Python オラクルとのパリティ含め
**全て CLEAN** (監査で確認、修正不要)。ロケール 4 言語のキー集合も一致を確認。

### 監査で確認した非バグ (誤検知防止メモ)
`PointSimulator` 0除算 / `ProductMatcher` janCode 欠落 / `BundlePackDetector` 0除算 /
`TCOCalculator` 負値 tcoPerMonth — いずれも**存在しない** (ガード済み or 到達不能)。

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

### 競合調査バックログ (同種 OSS 価格追跡アプリ由来)
GitHub 調査で確認した、競合にあり Popcoon に未実装だった機能。インパクト順:
- ~~**ウォッチリストの整理**: ソート・並べ替え~~ → 実装済み (#11 `WatchlistSort`:
  追加/価格/割引率/名前/目標到達順、永続化 + 並べ替えメニュー)。タグ付け/
  カテゴリ分けは未着手。
- ~~**URL 貼り付けで追加**: 共有インテント (`ACTION_SEND`)~~ → 既存 (`feature/share/
  UrlClassifier`, MainActivity で配線済み)。
- **クーポン/プロモコード集約**と決済前の自動適用 (Honey, Karma の中核機能)。
- **在庫アラート**: 再入荷/在庫切れ通知 (現状は価格のみ)。`Product.stockCount` は
  あるが追跡・通知導線がない。
- ~~**「追加時からの変動」表示**: ウォッチ追加時価格を基準に変動を可視化~~ → 実装済み
  (#12 `WatchlistPriceDelta` + Room v2→v3 `addedPrice` カラム + 行内表示)。
- **値下がりフィード**: ウォッチ外の急落商品を一覧する発見導線 (要 backend)。
- **多通貨対応**: 越境購入 (CustomsSimulator) と整合する通貨換算表示。

### 配線中に発見・修正した潜在バグ (CI 不在で未検出だったコンパイルエラー)
- `PriceSyncWorker` が `CurrencyFormatter` を import せず参照 (#10 で修正)。
- `UserPreferences` が `@Inject constructor` と `SettingsModule.@Provides` の二重
  バインディング (Hilt コンパイルエラー)。冗長な module provider を削除 (#11)。

### アルゴリズム (Tier 3)
- 価格予測を Holt 線形から Holt-Winters (季節性) へ。
- Kotlin↔Python 差分/契約テスト基盤 (CustomsSimulator / TCOCalculator / PricePredictionEngine /
  BuyTimingScorer の出力一致を CI で保証)。
- Kotlin 側ミューテーションテスト (Python は 100% kill 達成済み)。

### 設計判断 (要検討)
- TCOCalculator の残価が長期で 0% になる (現実は 5-15%) — 下限を設けるか要件確認。
- CustomsSimulator の関税丸めは truncate (日本の実務は切り上げが一般的) — 仕様確認。
