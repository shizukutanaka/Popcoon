# Popcoon 完成度報告

## プロダクト概要

Amazon / 楽天 / Yahoo! ショッピング横断の価格比較 Android アプリ。
日本市場向け。MIT ライセンスでオープンソース公開予定。

## 成果物

| 区分 | 数量 |
|---|---|
| 総ファイル数 | 346 |
| Kotlin ファイル | 199 (main 131 / unit test 64 / androidTest 4) |
| ユニットテスト | 64 |
| Compose UI テスト | 2 |
| Kotlin 行数 | ~16,400 |
| 言語対応 | 4 (ja / en / zh-CN / ko-KR) × 364 キー |
| CI ワークフロー | 0 稼働 (`ci/android.yml` に定義のみ — 下記「CI/CD」参照) |
| ストアリスティング | 4 言語 |
| ドキュメント | 23 (*.md + docs/*.md、2026-08 実測) |

## Python TDD 参照層 (別ディレクトリ)

| 指標 | 数値 |
|---|---|
| 行数 | 6,858 |
| テスト数 | 405 |
| カバレッジ | 99% (popcoon_core.py) |
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
- 価格通知 (端末ローカル通知、WorkManager 日次同期でトリガー)
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
- アラート評価 (AND/OR/NOT/price_below/atl/discount_pct ツリー、Cron 評価コードは実装済みだが
  Android クライアントが未接続のため配信は未稼働 — 詳細は ARCHITECTURE.md 参照)
- GDPR Article 17 削除
- クラッシュレポート受信 (PII 二重チェック)
- Rate limiting

## リリースまでの残作業 (2026-08 棚卸し — マスク流に「何が本当に止めているか」だけ書く)

**私 (自動化エージェント) 側で閉じられる作業は完了している。** 残る阻害要因は
**すべて人手ゲート**で、しかも実質 1 つに集約される。

### 唯一の技術的ブロッカー: app モジュールが一度もビルドされていない

Compose/Room/Hilt を含む **85 ファイル**がコンパイル検証されていない。
本環境で解けないことは推測ではなく **実測で確認済み** (2026-08):

| 確認項目 | 結果 |
|---|---|
| システム上の `android.jar` | 存在しない |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | いずれも未設定 |
| `dl.google.com` / `maven.google.com` / `repo1.maven.org` | いずれも到達不可 (curl 000) |

この穴が実害を出した実績がある: `UserPreferences` の 5 メンバーが `override` 欠落で
**約 1 か月コンパイル不能**だった (e519e67)。緩和策として
`run_compile_core.sh` (Android 非依存 34 ファイルの実コンパイル) と
`check_overrides.py` (全ソースの override 静的検査) を入れたが、
**残り 85 ファイルの型検査は CI でしか行えない**。

### 人がやること (順序どおり、所要は 1 コマンド + GitHub UI 操作)

| # | 作業 | コマンド / 手順 | これで初めて検証されるもの |
|---|---|---|---|
| 1 | **CI 有効化** | `bash ci/enable.sh && git push` | app 全体のコンパイル / kotest 単体テスト / R8 release ビルド / backend tsc |
| 2 | CI が緑になるまで修正 | 落ちたら本エージェントに差し戻し可 | — |
| 3 | v0.1.0 Release 作成 | GitHub → Releases → Draft (`main` から) | — |
| 4 | default branch を `main` へ | GitHub → Settings → Branches | — |
| 5 | (任意) リポジトリ public 化 | GitHub → Settings → Danger Zone | — |

**1 が全ての前提。** 2〜5 は 1 が緑になってから。

### 期限つきの外部要因

- **target API 36 (Android 16) が Google Play 必須: 2026-08-31**
  (延長申請で 11/01 まで)。コード移行は完了済みだが **ビルド検証が未了** —
  上記 1 を通さないと出せない。 <https://support.google.com/googleplay/android-developer/answer/11926878>
- Play Billing 8.3.0 移行も同様にコード完了 / ビルド未検証。

### 意図的に「やらない」と決めたもの (再検討の無駄打ちを防ぐため記録)

- **Amazon Creators API 移行** — OAuth2 資格情報 + 成果実績 (10 件/30 日) が必要な人手ゲート。
  現状 Amazon は FallbackScraper 依存 (2026-08 に多戦略化 + ¥0 捏造を修正済み)。
- **Firebase/FCM 組込み** — 製品判断。backend 側の配信経路は実装済みで、
  組み込めば生きる。組み込まない限り通知は端末ローカルのみ。
- **Durable Objects 移行** — `wrangler dev` 実行環境が前提。設計は backend/README.md に記録済み。

## CI/CD

※ 2026-07 の監査で訂正: 以下は当初「稼働中」として記載されていたが、実際に
`.github/workflows/` に存在し稼働しているものは無い。`android.yml` のみ
`ci/android.yml` に定義済み (detekt/lint/テスト/assembleDebug/assembleRelease) だが、
生成エージェントの GitHub App が `workflows` 権限を持たず push できず未稼働
(`ci/README.md` 参照、人間の push 権限での有効化待ち)。`python-tdd.yml` /
`codeql.yml` / `release.yml` は一度も実装されたことがないアスピレーショナルな記載
だった。`.github/dependabot.yml` (週次依存更新) のみこの制約と無関係に実稼働する。

1. `android.yml` (未稼働、`ci/` に定義のみ) — lint + test + assembleDebug
2. ~~`python-tdd.yml`~~ (未実装)
3. ~~`codeql.yml`~~ (未実装)
4. ~~`release.yml`~~ (未実装)

## 品質指標

- Python mutation score 100% × 4 modules
- Kotlin ユニットテスト 64 ファイル
- Compose UI テスト 2 ファイル
- Detekt 静的解析設定済み
- ProGuard/R8 ML Kit + CameraX ルール
- Baseline Profile 生成 (cold start 30-40% 短縮見込み)
- PrivacyCrashReporter opt-in + PII 自動除去
