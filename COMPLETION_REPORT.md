# Popcoon 完成度報告

## プロダクト概要

Amazon / 楽天 / Yahoo! ショッピング横断の価格比較 Android アプリ。
日本市場向け。MIT ライセンスでオープンソース公開予定。

## 成果物

| 区分 | 数量 |
|---|---|
| 総ファイル数 | ~155 |
| Kotlin ファイル | ~105 |
| ユニットテスト | ~28 |
| Compose UI テスト | 2 |
| Kotlin 行数 | ~10,000 |
| 言語対応 | 4 (ja / en / zh-CN / ko-KR) |
| CI ワークフロー | 4 |
| ストアリスティング | 4 言語 |
| ドキュメント | 8 (README / CHANGELOG / ARCHITECTURE / SECURITY / CONTRIBUTING / LICENSE / PRIVACY / COMPLETION_REPORT) |

## Python TDD 参照層 (別ディレクトリ)

| 指標 | 数値 |
|---|---|
| 行数 | 5,216 |
| テスト数 | 273 |
| カバレッジ | 98% |
| Mutation Score | 100% × 4 modules |
| 防御階層 | 11 |

## 機能一覧

### 独自機能 (競合 14 アプリ全社非搭載)

1. ダークパターン検出 (常設セール / 参考価格詐欺 / セール前値上げ / 端数価格)
2. TCO 5年計算 (プリンター / ノート PC / 冷蔵庫 / エアコン / コーヒーカプセル)
3. 越境 EC 関税シミュレーター (日本税関 10 カテゴリ + 16,666円免税)
4. AI 買い物アドバイザー (Claude claude-sonnet-5)
5. エコ倫理スコア (CO2 + 労働条件)
6. セット販売実質単価 (日本語タイトル解析)
7. オープンソース (MIT)
8. テレメトリゼロ

### 競合同等機能

- 3EC 横断比較 (Amazon / 楽天 / Yahoo!)
- バーコードスキャン (Google Code Scanner — 権限不要)
- 価格チャート (Compose Canvas 純描画)
- 価格通知 (ローカル + FCM 二重)
- ウォッチリスト + ホーム画面ウィジェット (Glance)
- ポイント還元シミュレーター (SPU / 5のつく日 / SoftBank)
- セールカレンダー (楽天スーパーセール / Prime Day / ブラックフライデー)
- Share Intent + App Links
- CSV エクスポート (Premium)
- App Shortcuts (3D Touch 相当)

### Apple HIG 適用 11 原則

1. BottomNavigationBar (タブ記憶)
2. スケルトンスクリーン (シマー)
3. 触覚フィードバック (4段階)
4. SwipeToDelete + Undo Snackbar
5. 段階的開示 ScoreCard (リングゲージ + タップ展開)
6. AnimatedContent 状態遷移
7. 検索オートコンプリート (Trie + 履歴)
8. 長押しコンテキストメニュー
9. 4 要素 Empty State
10. Pull-to-Refresh
11. App Shortcuts

## アーキテクチャ

- Jetpack Compose Material 3
- Hilt DI (4 モジュール: Network / Database / Settings / CoilImageLoader)
- Room DB (3 テーブル: Watchlist / SearchHistory / PriceCache)
- WorkManager (日次価格同期 + 指数バックオフ)
- DataStore Preferences
- Coil3 (メモリ 50MB + ディスク 200MB キャッシュ)
- ML Kit Code Scanner (権限不要)
- Glance (ホーム画面ウィジェット)
- Macrobenchmark + Baseline Profile
- AwsSigV4Signer (独立テスト可能)
- ApiResult<T> (構造化エラー型)
- PopcoonLogger (PII フィルタ統合 / リリースビルド自動無効化)
- IProductRepository interface (SOLID 原則)
- ConnectivityObserver + OfflineBanner

## Backend (Cloudflare Workers)

- 価格履歴 KV (365 日保持)
- アラート評価 (AND/OR/NOT/price_below/atl/discount_pct ツリー)
- FCM 通知配信
- GDPR Article 17 削除
- クラッシュレポート受信 (PII 二重チェック)
- Rate limiting

## CI/CD

1. `android.yml` — lint + test + assembleDebug
2. `python-tdd.yml` — 273 tests + coverage≥97% + 4 mutation suites
3. `codeql.yml` — Kotlin / JavaScript / Python 週次スキャン
4. `release.yml` — 署名付き AAB + Play Console internal track 自動 upload

## 品質指標

- Python mutation score 100% × 4 modules
- Kotlin ユニットテスト 28 ファイル
- Compose UI テスト 2 ファイル
- Detekt 静的解析設定済み
- ProGuard/R8 ML Kit + CameraX ルール
- Baseline Profile 生成 (cold start 30-40% 短縮見込み)
- PrivacyCrashReporter opt-in + PII 自動除去
