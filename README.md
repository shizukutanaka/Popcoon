# Popcoon

[![CI](https://github.com/shizukutanaka/popcoon/actions/workflows/android.yml/badge.svg)](https://github.com/shizukutanaka/popcoon/actions/workflows/android.yml)
[![CodeQL](https://github.com/shizukutanaka/popcoon/actions/workflows/codeql.yml/badge.svg)](https://github.com/shizukutanaka/popcoon/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-00C4CC.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)
[![Min SDK](https://img.shields.io/badge/minSdk-26-3DDC84.svg?logo=android)](https://developer.android.com)

Amazon / 楽天 / Yahoo! ショッピング横断の価格比較 Android アプリ。

## 特徴 (競合 14 アプリで非搭載の独自機能)

- **ダークパターン検出 4種** — 常設セール / 参考価格詐欺 / セール前値上げ / 端数価格を自動検出
- **TCO 5年計算** — プリンター / ノート PC / 冷蔵庫の総所有コストを考慮
- **越境 EC 関税試算** — 日本税関 10 カテゴリ + 16,666円免税ルール
- **AI 買い物アドバイザー** — Anthropic Claude が「今買うべきか」を 100 文字で助言
- **エコ倫理スコア** — CO2 + 製造国の労働条件を統合評価
- **セット販売実質単価** — 「3本セット ¥1,200」の実質単価を判定
- **オープンソース MIT** + **テレメトリゼロ**

## 競合と同水準の機能

- 3EC 横断比較 (Amazon・楽天・Yahoo!) を 1 タップで
- バーコードスキャン (Google Code Scanner — CAMERA 権限不要)
- 価格チャート (Compose Canvas 純描画)
- 価格通知 (端末ローカル通知、WorkManager 日次同期でトリガー)
- ウォッチリスト + ホーム画面ウィジェット
- ポイント還元シミュレーター (楽天 SPU / Yahoo 5のつく日 / SoftBank / Amazon)
- セールカレンダー (楽天スーパーセール / Prime Day / ブラックフライデー)
- Share Intent + App Links (他アプリから 2 タップで価格比較追加)

## Apple HIG 適用

- BottomNavigationBar (タブ記憶 + AnimatedVisibility 出し入れ)
- スケルトンスクリーン (シマー — スピナーの代替)
- 触覚フィードバック (light / success / heavy / warning)
- スワイプ削除 + Undo Snackbar (Forgiveness 原則)
- 段階的開示 ScoreCard (リングゲージ + タップで内訳展開)
- AnimatedContent 状態遷移フェード (200ms / 150ms)
- 検索オートコンプリート (Trie + 履歴)
- 長押しコンテキストメニュー
- 4 要素 Empty State (アイコン + 見出し + 説明 + CTA)
- App Shortcuts (3D Touch 相当)
- Pull-to-Refresh

## アーキテクチャ

```
Python TDD 参照層 (5,216 行 / 273 tests / 100% mutation × 4 modules)
  └─ Differential testing で Kotlin 本番実装と整合保証
     │
     ├─ Android アプリ (8,900 行 / 88 Kotlin / 21 テスト)
     │   ├─ Jetpack Compose Material 3
     │   ├─ Hilt DI (4 モジュール)
     │   ├─ Room DB (3 テーブル)
     │   ├─ WorkManager (日次価格同期)
     │   ├─ DataStore Preferences
     │   ├─ Coil3 (商品画像)
     │   ├─ ML Kit Code Scanner (権限不要)
     │   ├─ Glance (ホーム画面ウィジェット)
     │   ├─ Macrobenchmark + Baseline Profile
     │   └─ ApplicationStartInfo (Android 15 cold start 計測)
     │
     └─ Backend (Cloudflare Workers)
         ├─ 価格履歴 KV (365 日)
         ├─ アラート評価 (AND/OR/NOT/price_below/atl/discount_pct ツリー)
         ├─ GDPR Article 17 削除エンドポイント
         └─ Rate limiting (IP 別 1分5回)
```

## ディレクトリ構成

```
popcoon-android/
├── app/                      # Android アプリ
│   ├── src/main/java/io/github/shizukutanaka/popcoon/
│   │   ├── data/              # Repository / DB / Network
│   │   ├── feature/           # ビジネスロジック (10 機能)
│   │   ├── ui/                # Compose 画面 + コンポーネント
│   │   ├── widget/            # Glance ウィジェット
│   │   └── worker/            # WorkManager 価格同期
│   └── src/test/              # 21 ユニットテスト
├── baselineprofile/          # Baseline Profile + Macrobenchmark
├── backend/                  # Cloudflare Workers
├── store-listing/            # 4 言語ストアリスティング
└── .github/workflows/        # 4 CI ワークフロー
```

## ビルド / 実行

```bash
# 環境変数を設定
export AMAZON_ACCESS_KEY=...
export AMAZON_SECRET_KEY=...
export AMAZON_PARTNER_TAG=...
export RAKUTEN_APP_ID=...
export RAKUTEN_AFFILIATE_ID=...
export YAHOO_APP_ID=...
export YAHOO_SID=...
export ANTHROPIC_API_KEY=...

# Debug ビルド
./gradlew assembleDebug

# テスト
./gradlew test
./gradlew :baselineprofile:connectedAndroidTest

# Release ビルド (要署名鍵)
export KEYSTORE_PATH=/path/to/keystore.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
./gradlew bundleRelease
```

## バックエンドデプロイ

```bash
cd backend
npm install
npm run deploy   # wrangler deploy
```

## ライセンス

MIT — 詳細は [LICENSE](LICENSE) を参照
