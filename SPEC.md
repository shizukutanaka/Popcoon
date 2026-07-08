# Popcoon — 製品仕様書 (v1.0, 2026-06-20)

## 製品概要

**Popcoon** は、日本の EC 三大プラットフォーム (Amazon JP・楽天市場・Yahoo!ショッピング) の
価格を横断比較し、「買い時」を AI とデータで判定するプライバシーファーストの価格追跡 Android アプリ。

- **最小 Android**: API 26 (Android 8.0)
- **言語対応**: 日本語 / English / 한국어 / 简体中文
- **マネタイズ**: Freemium (月額 ¥480 / 年額 ¥3,800 のプレミアムサブスクリプション)
- **プライバシー**: 個人識別子なし、クラッシュレポートオプトイン、GDPR Article 17 準拠
- **テスト**: 54 テストファイル / 200+ Kotest spec / Python TDD オラクル 290 passed

---

## アーキテクチャ

```
UI Layer  │  Jetpack Compose (Material3)
──────────┼────────────────────────────────────────────────────────
ViewModel │  Hilt + StateFlow + viewModelScope
──────────┼────────────────────────────────────────────────────────
Feature   │  純関数モジュール (37 個、Android 非依存 → 単体テスト可)
──────────┼────────────────────────────────────────────────────────
Data      │  Room v4 (3テーブル) + DataStore + Retrofit/OkHttp
──────────┼────────────────────────────────────────────────────────
Backend   │  Cloudflare Workers (価格履歴プール / アラート評価)
──────────┼────────────────────────────────────────────────────────
Workers   │  WorkManager (日次価格同期 + 週次ダイジェスト)
```

---

## 機能一覧

### 画面 (8 画面)

| 画面 | ルート | 説明 |
|------|--------|------|
| 検索 | `search` | メイン画面。キーワード検索、バーコードスキャン、セールカレンダー入口 |
| 商品詳細 | `detail/{key}` | 全分析カード表示。買い時スコア、ダークパターン、AI アドバイス等 |
| ウォッチリスト | `watchlist` | 保存商品一覧。スワイプ削除、並べ替え、スマートカート最適化 |
| 設定 | `settings` | プライバシー設定、EC 会員設定、データ管理、プレミアム購入 |
| バーコード | `barcode` | JAN/EAN コードカメラスキャン → 直接商品詳細 or 検索 |
| セールカレンダー | `sale_calendar` | 今後の大型セール一覧 (楽天スーパーセール / プライムデー等) |
| 越境関税シミュレーター | `customs` | 海外購入時の関税・消費税・手数料計算 |
| オンボーディング | ─ | 初回起動のみ。機能説明 3 ページ |

### フィーチャーモジュール (37 個)

| カテゴリ | モジュール | 概要 | UI 配線 | テスト |
|----------|----------|------|---------|--------|
| **スコアリング** | BuyTimingScorer | 0–100 買い時スコア (価格百分位 + 季節性 + セール) | ✓ 詳細画面 | ✓ |
| | SeasonalDowSignal | 曜日・季節シグナル | ✓ (スコアラ経由) | ✓ |
| **予測** | PricePredictionEngine | Holt 平滑化 7日/30日予測 | ✓ 詳細画面 | ✓ |
| | ConformalInterval | 90% カバレッジ信頼区間 | ✓ (予測エンジン経由) | ✓ |
| | SeasonalDecompForecast | DLinear 型季節分解 | ✓ (予測エンジン経由) | ✓ |
| **ダークパターン** | DarkPatternDetector | 価格系 8 類型検出 (景品表示法対応) | ✓ 詳細画面 | ✓ |
| | DarkPatternTextDetector | テキスト系 5 類型 (URGENCY/SCARCITY 等) | ✓ 詳細画面 | ✓ |
| **通知** | PriceAlertEvaluator | エッジトリガ価格アラート (目標到達/値下がり率) | ✓ Worker | ✓ |
| | StockAlertEvaluator | 在庫変化アラート (入荷/売り切れ) | ⚠️ Worker 配線中 | ✓ |
| | LocalNotificationManager | ローカル通知発行 (価格/在庫/週次) | ✓ | ─ |
| | NotificationPermissionHelper | Android 13+ 権限管理 | ✓ ウォッチリスト | ─ |
| **ポイント** | PointSimulator | EC 会員設定を反映した実質価格計算 | ✓ 詳細画面 | ✓ |
| **AI** | BuyingAdvisor | Claude API 連携の買い物アドバイス (24h TTL LRU) | ✓ 詳細画面 | ✓ |
| | AdviceCache | LRU キャッシュ 100 件 | ✓ | ✓ |
| **EC** | AffiliateUrlBuilder | アフィリエイト URL 構築 (#ad バッジ付き) | ✓ 詳細画面 | ✓ |
| | CrossMallCartOptimizer | 3EC 横断カート最適化 | ✓ ウォッチリスト | ✓ |
| | SmartCartService | ウォッチリスト → 最適カート変換 | ✓ ウォッチリスト | ✓ |
| **カレンダー** | SaleCalendar | セールイベント (Major/Medium/Recurring) | ✓ 検索/カレンダー | ✓ |
| **品質** | ReviewTrustScorer | レビュー信頼度 (統計的サクラ検出) | ✓ 詳細画面 | ✓ |
| | BundlePackDetector | N個セット実質単価計算 | ✓ 詳細画面 | ✓ |
| | TCOCalculator | 5年間総保有コスト (プリンター/PC 等) | ✓ 詳細画面 | ✓ |
| | EcoEthicsScorer | 原産国の環境・労働倫理スコア | ✓ 詳細画面 | ✓ |
| | ProductMatcher | 商品名クロスプラットフォーム名寄せ | ⚠️ UI 未配線 | ✓ |
| **バーコード** | BarcodeScanner | JAN/EAN カメラスキャン | ✓ バーコード画面 | ─ |
| | JanCodeQuery | JAN コード → SKU 変換 | ✓ | ✓ |
| **共有** | UrlClassifier | 共有 URL からプラットフォーム/SKU 抽出 | ✓ MainActivity | ✓ |
| **出力** | PriceHistoryCsvExporter | 価格履歴 CSV エクスポート (Premium) | ✓ 設定画面 | ✓ |
| **越境** | CustomsSimulator | 関税・消費税・手数料計算 | ✓ 越境画面 | ✓ |
| **リテンション** | ReviewPrompter | Google Play レビュープロンプト (閾値 5 回/90日 CD) | ✓ 詳細画面 | ✓ |
| **課金** | BillingManager | Google Play Billing (月額/年額サブスク) | ✓ 設定画面 | ✓ |
| **クラッシュ** | PrivacyCrashReporter | 匿名・デバイス非紐付けクラッシュレポート | ✓ | ✓ |
| | StartupTracker | 起動イベント追跡 | ✓ | ✓ |
| **設定** | UserPreferences | DataStore バックド全設定管理 | ✓ 設定画面 | ─ |
| **ウォッチリスト** | WatchlistSort | 並べ替えモード (4種) | ✓ ウォッチリスト | ✓ |
| | WatchlistPriceDelta | 追加時からの価格変動計算 | ✓ ウォッチリスト | ✓ |
| | WidgetVerdict | ウィジェット用買い時判定 | ✓ ウィジェット | ✓ |

### データ層

| 区分 | 詳細 |
|------|------|
| **Room DB** | v4 (3テーブル: watchlist / search_history / price_cache) |
| **DataStore** | 全ユーザー設定 (opt-in 系 + EC 会員情報 + 行動履歴) |
| **ネットワーク** | Amazon PA-API v5 (SigV4) + 楽天 API + Yahoo API + FallbackScraper |
| **Backend** | Cloudflare Workers (価格プール / アラート評価 / GDPR 削除 noop) |

### バックグラウンド処理

| Worker | スケジュール | 処理 |
|--------|------------|------|
| PriceSyncWorker | 24h (Wi-Fi + 充電中優先) | 価格取得 → アラート評価 → 通知 → ウィジェット更新 |
| WeeklyDigestWorker | 7d | 値下がり件数集計 → 週次ダイジェスト通知 |

---

## 長所 (Strengths)

1. **プライバシーファースト設計** — デバイス ID なし、クラッシュレポートオプトイン、GDPR 対応。
   競合の CamelCamelCamel / Keepa が送る個人識別子を一切排除。

2. **豊富な分析機能** — 他の日本語価格追跡アプリにない機能群:
   - AI 買い物アドバイス (Claude API)
   - ダークパターン検出 (景品表示法対応)
   - 3EC 横断カート最適化
   - 価格予測 (Holt + 信頼区間)
   - レビュー信頼度 (サクラ検出)
   - 環境・倫理スコア
   - 越境関税シミュレーター

3. **高テストカバレッジ** — 54 テストファイル / 200+ Kotest spec。
   Python オラクルで Kotlin ↔ Python の差分パリティを 290 ケース検証。

4. **4ロケール完全対応** — JA / EN / KO / ZH-rCN で i18n パリティテスト付き。

5. **オフラインファースト** — 価格履歴ローカルキャッシュ + OfflineBanner + 
   WorkManager のネットワーク制約で過剰同期防止。

6. **アクセシビリティ** — TalkBack ラベル全画面、WCAG コントラスト検証テスト付き。

7. **EC 会員設定の個人化** — 楽天 SPU (1–15x)・Yahoo! Premium・SoftBank・Amazon Prime を
   反映した実質価格をリアルタイム計算。同種アプリで唯一の機能。

8. **法的コンプライアンス** — アフィリエイト #ad バッジ (景品表示法 §8)、
   ダークパターン検出 (JAA ガイドライン)、GDPR Article 17 端末内削除。

---

## 短所 (Weaknesses)

1. **在庫アラートが実質無効** — `StockAlertEvaluator` のロジックは完成・テスト済みだが、
   `PriceSyncWorker` がライブ在庫を取得せず、WatchlistItem に `stockAlertEnabled` 列もない。
   ユーザーは「入荷通知」を設定できない (= 最も要望の多い価格追跡機能の欠如)。

2. **商品マッチング UI が存在しない** — `ProductMatcher` は同一商品の複数 EC での
   名寄せロジックを持つが、検索結果画面に「この商品は Amazon で ¥X、楽天で ¥Y」
   という横断比較ビューが存在しない。

3. **通知設定の粒度が粗い** — 価格アラート / 週次ダイジェストの on/off が
   OS の通知チャンネル設定のみで、アプリ内に専用の通知設定画面がない。

4. **詳細画面に「シェア」ボタンがない** — `action_share` 文字列リソースは定義済みだが
   詳細画面にシェアボタンが存在しない。口コミ経由の獲得機会を逃している。

5. **価格アラート履歴が見えない** — 「いつ、何のアラートが届いたか」を確認できる
   UI が存在しない。ユーザーが通知を見逃した場合に遡れない。

6. **PriceChart の時間軸選択がない** — 価格履歴チャートに 1W/1M/3M/1Y の
   期間切替がなく、常に全期間表示になる。

7. **AI アドバイスのフィードバックがない** — 「役立った / 役立たなかった」ボタンがなく、
   `ReviewPrompter` の docstring に記載の成功イベントとして機能していない。

8. **ウォッチリスト画面に目標価格の現在値が見えない** — 目標価格設定後、
   リスト行に「目標: ¥X (現在 ¥Y)」のような達成度表示がない。

---

## 改善点と実装ロードマップ

### Priority 1 — 在庫アラート機能の完成 (StockAlertEvaluator の配線)

**現状**: ロジック完成・テスト済み。DB・Worker・UI の 3 層が未実装。  
**実装範囲**:
1. `WatchlistItem` に `previousInStock: Boolean?` + `stockAlertEnabled: Boolean` 追加
2. Room v4→v5 マイグレーション
3. `WatchlistDao` に `setStockAlertEnabled` + `updateStockState` 追加
4. `LocalNotificationManager.sendStockAlert()` 追加 + 4ロケール文字列
5. `PriceSyncWorker` に在庫チェック統合
6. WatchlistScreen に商品ごとの在庫アラートトグル UI

### Priority 2 — 詳細画面にシェアボタン追加

**現状**: 文字列 `action_share` 定義済み。実装 5 行のみ。  
**実装範囲**: `ProductDetailScreen` TopAppBar に `Share` アイコン + Android Intent.ACTION_SEND

### Priority 3 — PriceChart に時間軸選択

**現状**: 全期間表示のみ。  
**実装範囲**: `PriceChart` に `ChipGroup` (1W/1M/3M/ALL) + データフィルタリング

### Priority 4 — ウォッチリスト目標価格ステータス表示

**現状**: 目標価格はダイアログ設定のみで、リスト行に達成度が見えない。  
**実装範囲**: WatchlistItem 行に「目標 ¥X まで ¥Y」バッジ or 「✓ 目標達成」チップ

### Priority 5 — AI アドバイスフィードバックボタン

**現状**: 「役立った」ボタンなし。ReviewPrompter の成功イベントも取りこぼし中。  
**実装範囲**: AI アドバイスカードに 👍/👎 + `ReviewPrompter.recordSuccess()` 呼び出し

### Priority 6 — 通知設定画面

**現状**: OS チャンネル設定のみ。アプリ内で各種アラートの on/off 不可。  
**実装範囲**: Settings 内に通知セクション (価格アラート閾値スライダー、週次ダイジェスト on/off)

---

## テスト資産

| ファイル数 | 対象 |
|-----------|------|
| 54 | Kotlin 単体テスト (Kotest StringSpec/BehaviorSpec) |
| 1 | Python オラクル (290 ケース、数値計算の差分パリティ) |
| ─ | i18n パリティテスト (4ロケール × 269 キー整合性) |

---

## ファイル構成

```
app/src/main/java/io/github/shizukutanaka/popcoon/
├── data/
│   ├── db/PopcoonDatabase.kt       (3エンティティ + 3DAO + 4マイグレーション)
│   ├── model/                      (Product, PriceRecord)
│   ├── network/                    (Amazon/Rakuten/Yahoo クライアント + Mapper)
│   └── repository/                 (IProductRepository, ProductRepository, BackendClient)
├── feature/                        (37 フィーチャーモジュール、純関数中心)
├── ui/
│   ├── components/                 (19 再利用コンポーネント)
│   ├── screens/                    (8 画面 + ViewModel)
│   ├── theme/                      (Material3 テーマ + AppIcons + Spacing)
│   └── PopcoonNavGraph.kt          (8 ルート定義)
├── widget/                         (ホーム画面ウィジェット)
├── worker/                         (PriceSyncWorker, WeeklyDigestWorker)
├── core/                           (Logger, CurrencyFormatter, DeepLinks, ApiResult)
├── MainActivity.kt
└── PopcoonApp.kt
```
