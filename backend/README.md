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
   npm test      # vitest — 61 テスト (下記「テスト構成」参照)
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

## テスト構成

2026-07 の監査で発見: `@cloudflare/vitest-pool-workers` は package.json に
devDependency として存在していたが `vitest.config.ts` が無く、一度も有効化されて
いなかった。`alerts.test.ts` / `advice.test.ts` / `ratelimit.test.ts` はこれを前提に
`src/index.ts` の関数を直接 import せず、ロジックを手動で再実装して仕様として固定
する方針を取っていた (各ファイル冒頭のコメント参照) — 実際の `handleRequest` /
`evaluateAlerts` は一度も実行検証されていなかった。

`vitest.config.ts` を追加して `defineWorkersConfig` を有効化し、`worker.test.ts`
(新規) で実際に `import worker from "../src/index"` した本番ハンドラーを Miniflare
上で (ローカル・ネットワーク不要) 実行するテストを追加した。KV も実物の (ローカル)
`KVNamespace` 実装を使うため「書いた値が実際に読めるか」まで検証できる — admin
ゲート付き DELETE の実削除確認、アラート所有権チェック、条件ツリー深度バリデーション、
レート制限の 6 回目 429 等、再実装コピーでは原理的に検証できなかった経路を実ハンドラー
越しに固定している。

既存の再実装スタイルのテスト (`alerts.test.ts` 等) は削除していない — 純粋ロジックの
境界値・プロパティテストとして依然価値があり、`worker.test.ts` は HTTP 層の配線・KV
実処理・認可を実ハンドラーで検証する**補完**という位置づけ。

**ローカル Miniflare の既知の制限**:
- `wrangler.toml` の `[[ratelimits]]` (ネイティブ rate limit binding) はインストール済み
  vitest-pool-workers (0.5.41系) の内部 wrangler パーサが未対応で無視される
  ("Unexpected fields found in top-level field: ratelimits" 警告)。テスト内の
  `env.WRITE_RATE_LIMITER` 等は常に `undefined` になり、`rateLimit()` は常に KV
  フォールバック経路を通る — これはネイティブ binding が使えないデプロイ環境
  (古い wrangler 等) を想定した設計通りのフォールバックなので、意図的にその経路を
  検証している。ネイティブ binding 自体の実機検証は本番デプロイ後に別途必要。
- `compatibility_date = "2026-04-01"` はインストール済みランタイムの対応範囲外で
  `2024-12-30` にフォールバックする (警告のみ、テストは通る)。

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

## レート制限: ネイティブ binding への移行 (2026-07)

`wrangler.toml` に Workers Rate Limiting binding (2025-09 GA) を3本宣言済み
(`WRITE_RATE_LIMITER` 5/分、`READ_RATE_LIMITER` 30/分、`ADVICE_RATE_LIMITER` 3/分)。
`src/index.ts::rateLimit()` は binding があればそれを使い、無ければ従来の KV
カウンターにフォールバックする (古いデプロイを壊さない漸進移行)。

- binding は per-PoP の近似カウンター (厳密なグローバル上限ではない) だが、
  KV カウンターが持つ read-modify-write レース (同一分バケットへの同時リクエストが
  同じ count を読んで全部通過する) が無く、リクエスト毎の KV read+write も消費しない。
- binding が有効なデプロイでは `RATE_LIMIT` KV namespace は不要になる (削除は
  フォールバックを廃止する将来のタイミングで)。

## 価格履歴の lost-update 対策 (設計メモ、未実装)

`appendPriceHistory()` は KV の read→merge→put で、同一 `product_key` への同時
POST は後勝ちになり先行クライアントの記録が消える (lost update)。KV は結果整合の
ため根本解決はストレージ側の変更が必要。**無料プランでも Durable Objects (SQLite
backend) が使えるようになった (2025-04)** ため、正攻法は per-product DO への移行:

1. `PriceHistoryDO` クラス (SQLite backend) を追加し、`idFromName(product_key)` で
   商品毎に単一インスタンス化 — DO 内の逐次実行がレースを構造的に排除する。
2. DO 内は `ctx.storage.sql` に `(recorded_at TEXT PRIMARY KEY, platform TEXT,
   list_price INTEGER, real_price INTEGER)` の1テーブル。重複排除は PRIMARY KEY、
   365件制限は挿入後に `DELETE ... ORDER BY recorded_at ASC LIMIT -1 OFFSET 365`。
3. `POST /v1/history` は DO への `fetch()` 転送に置換。`GET` は移行期間中
   「DO に無ければ KV を読む」二段フォールバックにし、書き込み経由で徐々に DO へ
   移住させる (一括移行バッチは KV list の read 予算を食うため不要な限り避ける)。
4. 無料枠の注意: DO は 100k リクエスト/日・5GB SQLite 合計。現行の書き込み
   レート制限 (5/分/IP) の下では十分収まる。

本セッションで実装しなかった理由: wrangler ランタイムでの実行検証 (miniflare /
`wrangler dev`) が本環境で不可能で、DO クラスの export 追加・migrations 宣言
(`[[migrations]]` の `new_sqlite_classes`) はデプロイ設定と密結合しており、
検証なしで本番構成を書き換えるリスクが利益を上回るため。上記手順で別途実施を推奨。
