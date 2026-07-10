# Popcoon Backend (Cloudflare Workers)

価格履歴の集約保存・アラート評価/配信・AI アドバイザーの Claude API プロキシを担う
Cloudflare Workers。詳細な設計方針は `src/index.ts` 冒頭のコメントを参照。

## デプロイ前チェックリスト

`wrangler.toml` の `[[kv_namespaces]]` に書かれた `id = "${CLOUDFLARE_KV_..._ID}"` は
**プレースホルダーの生文字列であり、wrangler は環境変数をここに展開しません**
(商用リリース監査で発見 — この状態のまま `wrangler deploy` を実行すると失敗します)。
以下の手順で実 ID に置き換えてから初回デプロイしてください。

1. **KV namespace を作成し、実IDを `wrangler.toml` に直接書き込む**:
   ```bash
   wrangler kv:namespace create PRICE_HISTORY
   wrangler kv:namespace create DEVICE_TOKENS
   wrangler kv:namespace create ALERTS
   wrangler kv:namespace create RATE_LIMIT
   ```
   各コマンドの出力に含まれる `id = "..."` を、`wrangler.toml` の対応する
   `[[kv_namespaces]]` ブロックの `id = "${CLOUDFLARE_KV_..._ID}"` へ**手動で**
   上書きする (プレースホルダーのままでは deploy できない)。

2. **Secrets を設定する** (リポジトリには一切含まれない、Workers 側にのみ保存):
   ```bash
   wrangler secret put FCM_SERVER_KEY      # Firebase Cloud Messaging サーバーキー (プッシュ通知配信用)
   wrangler secret put ADMIN_API_KEY       # 管理 API 認証用 (内部)
   wrangler secret put ANTHROPIC_API_KEY   # Claude API (POST /v1/advice)。未設定でも
                                            # /v1/advice は 503 を返すだけで他は動く。
   ```

3. **依存関係をインストールし、テストを通す**:
   ```bash
   npm install   # 既知の注意点は下記「依存関係の既知の問題」参照
   npm test      # vitest — 34 テスト (アラート評価/PII検査/KVページネーション/advice検証等)
   npx tsc --noEmit   # 型チェック
   ```

4. **デプロイ**:
   ```bash
   npm run deploy   # = wrangler deploy
   ```

5. **Android アプリ側の `BACKEND_URL` を実デプロイ先に向ける**
   (`app/build.gradle.kts` の `buildConfigField("String", "BACKEND_URL", ...)`、
   既定値は `https://popcoon-backend.workers.dev` — 実際にこの URL にデプロイされて
   いない場合はビルド時 `BACKEND_URL` 環境変数で上書きする)。

## デプロイ後の動作確認

```bash
curl https://<デプロイ先>/v1/health
# => {"status":"ok","environment":"production"}
```

## 依存関係の既知の問題

このリポジトリには `package-lock.json` を最近まで含めていなかった。`npm install` を
素の状態で実行すると、`wrangler@^4.x` (peer: `@cloudflare/workers-types@^5`) と
`@cloudflare/vitest-pool-workers@^0.5` が内部で要求する `wrangler@3.100.0`
(peer: `@cloudflare/workers-types@^4`) が衝突し、`--legacy-peer-deps` なしでは
`npm install` が失敗する。`package-lock.json` をコミット済みのため通常は
`npm ci` で再現可能なはずだが、lockfile を再生成する場合はこの制約を踏まえること。
根本解決 (依存バージョンの整理) は別途の対応が必要。

## アラート評価の cron

`wrangler.toml` の `[triggers] crons = ["0 * * * *"]` により毎時 `scheduled()`
(`src/index.ts`) が起動し、登録済みアラートを評価して条件成立時に FCM 通知を送る
ロジックが実装されている。ただし Android クライアントは Firebase SDK を組み込んで
おらず `/v1/device` にデバイストークンを登録することが一切ないため、この経路は
現状 backend 単体で完結する死コードであり、実際に通知は届かない (詳細は
リポジトリルートの ARCHITECTURE.md 参照)。`FCM_SERVER_KEY` 未設定の場合は通知送信を
スキップする (エラーにはならない) — Android 統合前は未設定のままで構わない。
