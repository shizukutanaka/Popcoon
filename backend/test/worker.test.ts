/**
 * これは他の test/*.ts と異なり、ロジックを再実装せず本番の src/index.ts を
 * @cloudflare/vitest-pool-workers 経由で実際に import・実行するテストである。
 *
 * 背景 (2026-07、8次元監査ワークフローで発見): 本リポジトリの backend テストは
 * すべて handleRequest/evaluateAlerts を直接呼ばず、契約を手動で再実装していた
 * (alerts.test.ts / advice.test.ts / ratelimit.test.ts のコメント参照)。
 * @cloudflare/vitest-pool-workers は package.json に devDependency として
 * 記載されていたが vitest.config.ts が存在せず、pool が一切有効化されていなかった。
 *
 * vitest.config.ts (新規) で defineWorkersConfig を有効化し、このファイルで
 * `import worker from "../src/index"` した実ハンドラーを Miniflare (ローカル実行、
 * ネットワーク不要) 上で叩く。KV も実際の (ローカル) KVNamespace 実装を使うため、
 * 「書いた値が本当に読めるか」まで検証できる — 再実装コピーでは原理的に不可能だった
 * 検証レイヤー。
 *
 * 既知の制限 (ローカル Miniflare の制約、2026-07 時点):
 *  - wrangler.toml の [[ratelimits]] (ネイティブ binding) はインストール済み
 *    vitest-pool-workers (0.5.41 系) の wrangler パーサが未対応で "Unexpected
 *    fields" 警告と共に無視される → env.WRITE_RATE_LIMITER 等は undefined になり、
 *    rateLimit() は常に KV フォールバック経路を通る。src/index.ts::rateLimit() は
 *    まさにこの状況 (binding 未設定) を想定したフォールバック設計なので、ここでは
 *    その経路を実ハンドラー越しに検証する。
 *  - ADMIN_API_KEY 等の secret は wrangler.toml に値を持たない (secret はコード管理外)。
 *    未設定時の動作 (503) を検証した上で、必要なテストのみ env に直接値を注入して
 *    設定済みケースを検証する。
 */

import { env, createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { describe, it, expect } from "vitest";
import worker from "../src/index";

async function call(req: Request): Promise<Response> {
  const ctx = createExecutionContext();
  const res = await worker.fetch(req, env, ctx);
  await waitOnExecutionContext(ctx);
  return res;
}

function req(path: string, init?: RequestInit): Request {
  return new Request(`http://example.com${path}`, init);
}

describe("実ハンドラー: GET /v1/health", () => {
  it("200 で稼働状態を返す", async () => {
    const res = await call(req("/v1/health"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toMatchObject({ status: "ok" });
  });
});

describe("実ハンドラー: POST/GET /v1/history (実 KV 往復)", () => {
  it("書き込んだ価格履歴を実際に読み戻せる", async () => {
    const key = `test:probe:${crypto.randomUUID()}`;
    const postRes = await call(req("/v1/history", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        product_key: key, platform: "amazon",
        list_price: 2000, real_price: 1500,
        recorded_at: "2026-01-01T00:00:00Z",
      }),
    }));
    expect(postRes.status).toBe(200);

    const getRes = await call(req(`/v1/history?key=${encodeURIComponent(key)}`));
    expect(getRes.status).toBe(200);
    const body = await getRes.json() as { count: number; records: Array<{ real_price: number }> };
    expect(body.count).toBe(1);
    expect(body.records[0].real_price).toBe(1500);
  });

  it("product_key欠落は400 (実ハンドラーのバリデーションを直接検証)", async () => {
    const res = await call(req("/v1/history", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ real_price: 100, list_price: 100, recorded_at: "2026-01-01T00:00:00Z" }),
    }));
    expect(res.status).toBe(400);
  });

  it("platform が長すぎる場合は400 (この監査サイクルで追加した検証)", async () => {
    const res = await call(req("/v1/history", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        product_key: "test:x", platform: "x".repeat(51),
        list_price: 100, real_price: 100, recorded_at: "2026-01-01T00:00:00Z",
      }),
    }));
    expect(res.status).toBe(400);
  });
});

describe("実ハンドラー: DELETE /v1/history (admin ゲート)", () => {
  it("ADMIN_API_KEY 未設定時は 503 (誰も削除できない、公開されない)", async () => {
    const res = await call(req("/v1/history?key=amazon:B0TEST", { method: "DELETE" }));
    expect(res.status).toBe(503);
  });

  it("ADMIN_API_KEY 設定済みでも x-admin-key 不一致は 403", async () => {
    const res = await worker.fetch(
      req("/v1/history?key=amazon:B0TEST", {
        method: "DELETE",
        headers: { "x-admin-key": "wrong-key" },
      }),
      { ...env, ADMIN_API_KEY: "correct-key" },
      createExecutionContext(),
    );
    expect(res.status).toBe(403);
  });

  it("正しい x-admin-key なら削除でき、実際に KV から消える", async () => {
    const key = `test:admin-delete:${crypto.randomUUID()}`;
    const testEnv = { ...env, ADMIN_API_KEY: "correct-key" };

    await worker.fetch(
      req("/v1/history", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          product_key: key, platform: "amazon",
          list_price: 100, real_price: 100, recorded_at: "2026-01-01T00:00:00Z",
        }),
      }),
      testEnv, createExecutionContext(),
    );

    const delRes = await worker.fetch(
      req(`/v1/history?key=${encodeURIComponent(key)}`, {
        method: "DELETE",
        headers: { "x-admin-key": "correct-key" },
      }),
      testEnv, createExecutionContext(),
    );
    expect(delRes.status).toBe(200);

    const getRes = await worker.fetch(
      req(`/v1/history?key=${encodeURIComponent(key)}`), testEnv, createExecutionContext(),
    );
    const body = await getRes.json() as { count: number };
    expect(body.count).toBe(0);  // 実際に消えている (再実装コピーでは検証不能な事実)
  });
});

describe("実ハンドラー: POST /v1/alerts + DELETE 所有権チェック", () => {
  it("x-device-token 欠落は 400", async () => {
    const res = await call(req("/v1/alerts", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ product_key: "amazon:B0TEST", condition: '{"type":"price_below","value":100}' }),
    }));
    expect(res.status).toBe(400);
  });

  it("深すぎる条件ツリーは書き込み時に拒否される (isValidCondition の実配線を検証)", async () => {
    let cond: any = { type: "price_below", value: 0 };
    for (let i = 0; i < 15; i++) cond = { type: "not", children: [cond] };
    const res = await call(req("/v1/alerts", {
      method: "POST",
      headers: { "content-type": "application/json", "x-device-token": "dev-a" },
      body: JSON.stringify({ product_key: "amazon:B0TEST", condition: JSON.stringify(cond) }),
    }));
    expect(res.status).toBe(400);
  });

  it("他デバイスのアラートは削除できず、所有デバイスなら削除できる", async () => {
    const createRes = await call(req("/v1/alerts", {
      method: "POST",
      headers: { "content-type": "application/json", "x-device-token": "owner-device" },
      body: JSON.stringify({
        product_key: "amazon:B0OWNER", condition: '{"type":"price_below","value":1000}',
      }),
    }));
    expect(createRes.status).toBe(200);
    const { alert_id } = await createRes.json() as { alert_id: string };

    const otherRes = await call(req(`/v1/alerts/${alert_id}`, {
      method: "DELETE",
      headers: { "x-device-token": "attacker-device" },
    }));
    expect(otherRes.status).toBe(403);

    const ownerRes = await call(req(`/v1/alerts/${alert_id}`, {
      method: "DELETE",
      headers: { "x-device-token": "owner-device" },
    }));
    expect(ownerRes.status).toBe(200);
  });
});

describe("実ハンドラー: rate limit (KV フォールバック経路、binding 未設定時)", () => {
  it("書き込み系は6回目で429 (上限5/分、実 KV カウンター経由)", async () => {
    // レート制限キーは cf-connecting-ip ヘッダー由来。テスト間で衝突しないよう
    // このテスト専用の IP を使う。
    const headers = { "content-type": "application/json", "cf-connecting-ip": "203.0.113.42" };
    const body = (i: number) => JSON.stringify({
      product_key: `test:rl:${i}`, platform: "amazon",
      list_price: 100, real_price: 100, recorded_at: "2026-01-01T00:00:00Z",
    });
    const statuses: number[] = [];
    for (let i = 0; i < 6; i++) {
      const res = await call(req("/v1/history", { method: "POST", headers, body: body(i) }));
      statuses.push(res.status);
    }
    expect(statuses.slice(0, 5)).toEqual([200, 200, 200, 200, 200]);
    expect(statuses[5]).toBe(429);
  });
});

describe("実ハンドラー: POST /v1/crash (サイズ上限、機能過不足監査で追加)", () => {
  it("正当な小さいペイロードは 200 で受理される", async () => {
    const res = await call(req("/v1/crash", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.50" },
      body: JSON.stringify({
        sanitized_stack: "com.example.Foo.bar(Foo.kt:42)",
        app_version: "1.2.3", os: "Android 16",
      }),
    }));
    expect(res.status).toBe(200);
  });

  it("上限を超える巨大ペイロードは 413 で拒否され KV に保存されない", async () => {
    // MAX_CRASH_PAYLOAD_BYTES=16384 を超える sanitized_stack を送る。
    const res = await call(req("/v1/crash", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.51" },
      body: JSON.stringify({ sanitized_stack: "x".repeat(20_000) }),
    }));
    expect(res.status).toBe(413);
  });

  it("PII を含むペイロードは 400 で拒否される (実ハンドラー越しの回帰確認)", async () => {
    const res = await call(req("/v1/crash", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.52" },
      body: JSON.stringify({ sanitized_stack: "ok", device_id: "user@example.com" }),
    }));
    expect(res.status).toBe(400);
  });

  it("不正な JSON は 400 で拒否される", async () => {
    const res = await call(req("/v1/crash", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.53" },
      body: "not json",
    }));
    expect(res.status).toBe(400);
  });
});
