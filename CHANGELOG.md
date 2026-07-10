# 変更履歴

## [0.1.0] - 2026-04-25 (Unreleased)

### Android アプリ

#### コアロジック (Python TDD → Kotlin 移植)
- 価格予測 (Holt's linear smoothing + IQR 外れ値除去)
- ダークパターン検出 (常設セール / 参考価格誇張 / セール前値上げ / 端数価格)
- 買い時スコア (6 シグナル統合: ATL / トレンド / 割引 / ボラティリティ / 履歴 / 罠)
- セット販売分析 (日本語タイトル解析、実質単価判定)
- TCO 計算 (5年総所有コスト)
- 越境 EC 関税シミュレーター (日本税関 10 カテゴリ)
- エコ倫理スコア (CO2 × 労働条件)
- ポイント還元シミュレーター (楽天 SPU / Yahoo 5のつく日 / SoftBank / Amazon)
- セールカレンダー (楽天スーパーセール / Prime Day / ブラックフライデー 等)

#### EC API 統合
- Amazon PA-API 5.0 (AWS SigV4 純 Kotlin 実装)
- 楽天 Ichiba API
- Yahoo! ショッピング API v3
- FallbackScraper (JSON-LD 倫理的スクレイプ)
- ProductRepository (3EC 並列 async/await)

#### UI (Jetpack Compose Material 3)
- BottomNavigationBar (Apple タブバー相当: Search / お気に入り / 設定)
- SearchScreen (debounce 300ms + distinctUntilChanged)
  - オートコンプリート (Trie + 検索履歴)
  - セールバナー (今日のセール状況)
  - 商品画像 (Coil3 非同期読み込み + シマープレースホルダー)
  - 長押しコンテキストメニュー (お気に入り追加 / 共有)
  - AnimatedContent 状態遷移 (Idle / Loading / Results / Empty / Error)
  - スケルトンスクリーン (シマーエフェクト)
- ProductDetailScreen
  - ScoreCard (段階的開示: リングゲージ + タップで内訳展開)
  - 価格チャート (Compose Canvas 純描画)
  - ポイント還元実質価格カード
  - ウォッチリスト ★ ボタン (触覚フィードバック付き)
  - AI アドバイス (背景取得で UI ブロックなし)
- WatchlistScreen
  - スワイプ削除 + Undo Snackbar (Forgiveness 原則)
  - Empty state (Apple 4 要素: アイコン + 見出し + 説明 + CTA)
- OnboardingScreen (3 ステップ HorizontalPager)
- SettingsScreen (GDPR 削除 / クラッシュレポート opt-in / Premium)
- BarcodeScreen (Google Code Scanner — CAMERA 権限不要)
- Home widget (Glance API)

#### Apple HIG 適用
- シマーエフェクト (スピナーの代替)
- 触覚フィードバック (light / success / heavy / warning)
- SwipeToDelete + Undo Snackbar
- 段階的開示 ScoreCard
- Empty state 4 要素パターン
- Pull-to-Refresh
- AnimatedContent 状態遷移フェード
- BottomNavigationBar (タブ記憶)
- コンテキストメニュー (長押し)
- オートコンプリート (Trie + 履歴)

#### バックエンド (Cloudflare Workers)
- 価格履歴 API (365 日保持)
- アラート条件評価 (AND/OR/NOT/price_below/price_above/atl/discount_pct ツリー)
- アラート条件評価 → FCM プッシュ送信ロジック (Android クライアント未接続のため現状未稼働。詳細は ARCHITECTURE.md 参照)
- GDPR Article 17 削除エンドポイント
- Rate limiting (IP 別 1分5回)
- クラッシュレポート受信 (PII 二重チェック)
- ScheduledEvent (毎時アラート評価)

#### CI/CD
- android.yml (lint + test + assembleDebug)
- python-tdd.yml (273 tests + coverage 97% + 4 mutation suites + 5x flaky)
- codeql.yml (Kotlin / JS / Python 週次スキャン)
- release.yml (署名付き AAB + Play Console internal track 自動アップロード)
- Dependabot (Gradle / npm / Actions / pip)

#### 品質
- 273 Python tests / 98% coverage
- 100% mutation score × 4 modules (41 mutants)
- 11 階層防御
- Kotlin unit tests (15 ファイル / property-based)
- Room migration 安全性テスト
- DatabaseIntegrity test

#### 多言語 / ストア
- 4 言語 (ja / en / zh-CN / ko-KR) × 60+ string keys
- ストアリスティング 4 言語 (タイトル/短説明/長説明/キーワード/宣伝文)

#### プライバシー
- テレメトリゼロ
- PrivacyCrashReporter (opt-in / PII 自動除去 / offline queue)
- GDPR Article 17 完全実装
- backup_rules.xml: バックアップ無効化
- Play Console Data Safety 宣言対応

### 発見・修正したバグ
1. Trie.suggest O(n²) → deque O(1) (14× 高速化)
2. predict_price `records[-2]` → `records[-1]` (current_price 誤り)
3. pytest-repeat × hypothesis 互換性問題
4. BuyTimingScorer 重み積算の数学的不整合
5. TCO 消耗品計算の手計算ズレ
6. @ApplicationContext DI 欠落 (UserPreferences コンパイルエラー)
