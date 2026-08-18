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

import {
  env,
  createExecutionContext,
  waitOnExecutionContext,
  createScheduledController,
} from "cloudflare:test";
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

describe("実ハンドラー: DELETE /v1/device (GDPR Article 17、プライバシー中核)", () => {
  it("x-device-token 欠落は 400", async () => {
    const res = await call(req("/v1/device", { method: "DELETE" }));
    expect(res.status).toBe(400);
  });

  it("自デバイスのアラートのみ削除し、他デバイスのアラートは残す", async () => {
    // 2 デバイス分のアラートを作成
    const mkAlert = async (token: string, key: string) => {
      const res = await call(req("/v1/alerts", {
        method: "POST",
        headers: { "content-type": "application/json", "x-device-token": token },
        body: JSON.stringify({ product_key: key, condition: '{"type":"price_below","value":500}' }),
      }));
      expect(res.status).toBe(200);
      return (await res.json() as { alert_id: string }).alert_id;
    };
    const mineId = await mkAlert("gdpr-me", "amazon:B0MINE");
    const othersId = await mkAlert("gdpr-other", "amazon:B0OTHER");

    // 自デバイスのデータ削除
    const del = await call(req("/v1/device", {
      method: "DELETE",
      headers: { "x-device-token": "gdpr-me" },
    }));
    expect(del.status).toBe(200);

    // 自分のアラートは消え (所有者削除は 200 冪等)、他人のアラートは残る (所有者のみ削除可)
    const mineGone = await call(req(`/v1/alerts/${mineId}`, {
      method: "DELETE", headers: { "x-device-token": "gdpr-me" },
    }));
    expect(mineGone.status).toBe(200);  // 既に無い = 冪等成功

    const othersStill = await call(req(`/v1/alerts/${othersId}`, {
      method: "DELETE", headers: { "x-device-token": "gdpr-me" },
    }));
    expect(othersStill.status).toBe(403);  // 他デバイス所有のため削除拒否 = まだ存在する証拠
  });
});

describe("実ハンドラー: OPTIONS (CORS preflight)", () => {
  it("preflight は許可メソッド/ヘッダーを返し、x-admin-key を含む", async () => {
    const res = await call(req("/v1/history", { method: "OPTIONS" }));
    expect(res.status).toBe(200);
    expect(res.headers.get("access-control-allow-origin")).toBe("*");
    const allowHeaders = res.headers.get("access-control-allow-headers") ?? "";
    // 管理用 DELETE /v1/history はブラウザからのプリフライトで x-admin-key を要求する
    expect(allowHeaders).toContain("x-admin-key");
    expect(allowHeaders).toContain("x-device-token");
    expect(res.headers.get("access-control-allow-methods")).toContain("DELETE");
  });
});

describe("実ハンドラー: scheduled() 毎時 cron (evaluateAlerts の one-shot 発火)", () => {
  // アラート KV を直接読んで active フラグを検証する (観測用エンドポイントが無いため)。
  async function readAlertActive(alertId: string): Promise<boolean | null> {
    const raw = await env.ALERTS.get(`alert:${alertId}`);
    if (!raw) return null;
    return (JSON.parse(raw) as { active: boolean }).active;
  }

  async function createAlert(token: string, productKey: string, value: number, ip: string): Promise<string> {
    const res = await call(req("/v1/alerts", {
      method: "POST",
      headers: { "content-type": "application/json", "x-device-token": token, "cf-connecting-ip": ip },
      body: JSON.stringify({ product_key: productKey, condition: JSON.stringify({ type: "price_below", value }) }),
    }));
    expect(res.status).toBe(200);
    return (await res.json() as { alert_id: string }).alert_id;
  }

  async function seedHistory(productKey: string, realPrice: number, ip: string): Promise<void> {
    const res = await call(req("/v1/history", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": ip },
      body: JSON.stringify({
        product_key: productKey, platform: "amazon",
        list_price: 1000, real_price: realPrice, recorded_at: "2026-01-01T00:00:00Z",
      }),
    }));
    expect(res.status).toBe(200);
  }

  async function runCron(): Promise<void> {
    const ctrl = createScheduledController({ scheduledTime: 0, cron: "0 * * * *" });
    const ctx = createExecutionContext();
    await worker.scheduled!(ctrl, env, ctx);
    await waitOnExecutionContext(ctx);
  }

  it("条件を満たすアラートは cron 後に非アクティブ化される (one-shot)", async () => {
    const id = await createAlert("cron-fire", "amazon:B0CRONFIRE", 1000, "203.0.113.60");
    await seedHistory("amazon:B0CRONFIRE", 800, "203.0.113.60");  // 800 <= 1000 → 発火
    expect(await readAlertActive(id)).toBe(true);

    await runCron();

    expect(await readAlertActive(id)).toBe(false);
  });

  it("条件を満たさないアラートは cron 後もアクティブなまま", async () => {
    const id = await createAlert("cron-nofire", "amazon:B0CRONNOFIRE", 500, "203.0.113.61");
    await seedHistory("amazon:B0CRONNOFIRE", 800, "203.0.113.61");  // 800 <= 500 は偽 → 不発火
    expect(await readAlertActive(id)).toBe(true);

    await runCron();

    expect(await readAlertActive(id)).toBe(true);
  });
});

describe("実ハンドラー: DELETE /v1/alerts/{id} (所有者検証、ルート全体が未テストだった)", () => {
  // このルートは「ID が漏れた場合の無認可削除」を防ぐために device_token 照合を
  // 追加した経緯があるが、テストが 1 件も無かった (2026-08 の棚卸しで発覚)。
  // 所有者検証は KV の実挙動 (書いた値が読めるか) に依存するため、
  // 再実装コピーではなく実ハンドラー + 実 KV で固定する。
  async function seedAlert(id: string, deviceToken: string) {
    await env.ALERTS.put(`alert:${id}`, JSON.stringify({
      alert_id: id,
      device_token: deviceToken,
      product_key: "amazon:B0TEST",
      condition: { type: "price_below", value: 1000 },
    }));
  }

  it("x-device-token 欠落は 400", async () => {
    const res = await call(req("/v1/alerts/some-id", { method: "DELETE" }));
    expect(res.status).toBe(400);
  });

  it("存在しない alert_id は 200 (冪等に成功扱い)", async () => {
    const res = await call(req("/v1/alerts/does-not-exist-2026", {
      method: "DELETE",
      headers: { "x-device-token": "tok-owner" },
    }));
    expect(res.status).toBe(200);
  });

  it("他人の device_token では 403 で拒否され、KV から消えない (中核の権限チェック)", async () => {
    await seedAlert("owned-by-a", "tok-a");
    const res = await call(req("/v1/alerts/owned-by-a", {
      method: "DELETE",
      headers: { "x-device-token": "tok-b" },
    }));
    expect(res.status).toBe(403);
    // 実 KV に残っていることまで確認する (403 を返すだけで消していたら意味がない)
    expect(await env.ALERTS.get("alert:owned-by-a")).not.toBeNull();
  });

  it("正しい device_token なら 200 で実際に KV から削除される", async () => {
    await seedAlert("owned-by-c", "tok-c");
    const res = await call(req("/v1/alerts/owned-by-c", {
      method: "DELETE",
      headers: { "x-device-token": "tok-c" },
    }));
    expect(res.status).toBe(200);
    expect(await env.ALERTS.get("alert:owned-by-c")).toBeNull();
  });

  it("保存値が壊れた JSON でも 403 で安全側に倒れる (削除しない)", async () => {
    await env.ALERTS.put("alert:corrupt-json", "{ not valid json");
    const res = await call(req("/v1/alerts/corrupt-json", {
      method: "DELETE",
      headers: { "x-device-token": "tok-any" },
    }));
    expect(res.status).toBe(403);
    expect(await env.ALERTS.get("alert:corrupt-json")).not.toBeNull();
  });
});

describe("実ハンドラー: ルーティングのフォールスルー", () => {
  it("未知のパスは 404", async () => {
    const res = await call(req("/v1/nope"));
    expect(res.status).toBe(404);
  });

  it("既知パスでも非対応メソッドなら 404 (メソッド違いで別ルートに落ちない)", async () => {
    // /v1/health は GET のみ。POST は他のどのルートにも該当せず 404 になる。
    const res = await call(req("/v1/health", { method: "POST" }));
    expect(res.status).toBe(404);
  });

  it("/v1/alerts/ の末尾スラッシュのみは alert_id 空として扱われる", async () => {
    const res = await call(req("/v1/alerts/", {
      method: "DELETE",
      headers: { "x-device-token": "tok-x" },
    }));
    expect(res.status).toBe(400);
  });
});

describe("実ハンドラー: PII 検出の IPv4 分岐 (実ハンドラー越しでは未検証だった)", () => {
  // alerts.test.ts は containsPotentialPii を **再実装したコピー** で IPv4 を
  // 検証しており、本番コードの分岐は実ハンドラー越しに通っていなかった。
  it("スタックに IPv4 が含まれると 400 で拒否される", async () => {
    const res = await call(req("/v1/crash", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.60" },
      body: JSON.stringify({ sanitized_stack: "connect failed to 192.168.11.24" }),
    }));
    expect(res.status).toBe(400);
  });

  it("IP に見えないドット区切り数値 (バージョン番号) は誤検出しない", async () => {
    const res = await call(req("/v1/crash", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.61" },
      body: JSON.stringify({ sanitized_stack: "ok", app_version: "1.2.3" }),
    }));
    expect(res.status).toBe(200);
  });
});

describe("実ハンドラー: POST /v1/history は ¥0 を拒否する (2026-08 の価格汚染対策)", () => {
  // 0 は「無料商品」ではなく取得失敗の痕跡。履歴に入ると BuyTimingScorer の
  // 「過去最安値到達」判定が反転する (95/BUY_NOW → 40/NEUTRAL) ため書き込み側で弾く。
  // クライアントの parsePriceToLong も同じ規約 (<=0 は失敗) で揃えてある。
  function post(body: Record<string, unknown>) {
    return call(req("/v1/history", {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.70" },
      body: JSON.stringify(body),
    }));
  }
  const base = {
    product_key: "amazon:B0ZERO", platform: "amazon",
    list_price: 5000, recorded_at: "2026-08-01T00:00:00Z",
  };

  it("real_price = 0 は 400 で拒否される", async () => {
    expect((await post({ ...base, real_price: 0 })).status).toBe(400);
  });

  it("real_price が負でも 400", async () => {
    expect((await post({ ...base, real_price: -1 })).status).toBe(400);
  });

  it("正の real_price は従来どおり受理される", async () => {
    expect((await post({ ...base, real_price: 4900 })).status).toBe(200);
  });

  it("list_price = 0 は引き続き許容 (定価不明を 0 で表す既存の規約)", async () => {
    expect((await post({ ...base, real_price: 4900, list_price: 0 })).status).toBe(200);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// PII 二重チェックはクライアントと同じ 9 種類を見なければならない (2026-08)
//
// サーバー側は以前 email と IPv4 の 2 種類しか見ておらず、クライアント
// (PrivacyCrashReporter.sanitizeStack) が除去している残り 7 種類 —
// 電話番号 / Authorization / API キー・トークン・パスワード / AWS アクセスキー /
// 端末内ユーザーパス / URL クエリ — が素通りしていた。「二重チェック」を名乗る以上、
// 対象はクライアントと揃っている必要がある (古い/改造クライアント対策がこの層の役割)。
//
// すべて **実ハンドラー越し** に検証する。alerts.test.ts の同種テストは
// containsPotentialPii を再実装したコピーを対象にしており、本番コードを通らない。
// ─────────────────────────────────────────────────────────────────────────────
describe("実ハンドラー: PII 二重チェックはクライアントの除去対象と揃っている", () => {
  const cases: Array<[string, string]> = [
    ["電話番号 (国内)", "SmsService.send(09012345678)"],
    ["電話番号 (国際 +81)", "SmsService.send(+81 90 1234 5678)"],
    ["Authorization ヘッダ", "at Http.kt: Authorization: Bearer abc123def456"],
    ["API キー", 'Config(api_key="sk-live-abcdef123456")'],
    ["token", "IllegalStateException: token=eyJhbGciOiJIUzI1NiJ9"],
    ["password", "LoginRequest(password=hunter2)"],
    ["AWS アクセスキー", "S3Client init AKIAIOSFODNN7EXAMPLE"],
    ["端末内ユーザーパス (/data/user)", "open /data/user/0/com.example/files/tanaka_memo.txt"],
    ["端末内ユーザーパス (/storage/emulated)", "read /storage/emulated/0/TanakaShizuku"],
    ["URL クエリ", "GET https://api.example.com/v1?user=shizuku&key=secret failed"],
  ];

  let ipCounter = 100;
  for (const [name, stack] of cases) {
    it(`${name} を含むペイロードは 400 で拒否される`, async () => {
      const res = await call(req("/v1/crash", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "cf-connecting-ip": `203.0.113.${ipCounter++}`,
        },
        body: JSON.stringify({ sanitized_stack: stack }),
      }));
      expect(res.status).toBe(400);
    });
  }

  // 冪等性が要件: クライアントが正しくサニタイズしたペイロードは通らなければならない。
  // 除去パターンが自分の置換結果に再マッチすると、正当なレポートを全て拒否してしまう。
  it("クライアントがサニタイズ済みのペイロードは通る (置換後の文字列に再マッチしない)", async () => {
    const sanitized = [
      "at Http.kt: Authorization: [redacted]",
      'Config(api_key="[redacted]")',
      "SmsService.send([tel])",
      "connect to [ip] failed",
      "mail [email]",
      "open /data/user/0/[pkg]/files/[user]",
      "read /storage/emulated/[u]/[user]",
      "GET https://api.example.com/v1?user=[redacted]&key=[redacted]",
      "S3Client init [aws-key]",
    ].join("\n");
    const res = await call(req("/v1/crash", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "203.0.113.150",
      },
      body: JSON.stringify({ sanitized_stack: sanitized, app_version: "1.2.3" }),
    }));
    expect(res.status).toBe(200);
  });

  it("通常のスタックトレースは誤検出しない", async () => {
    const res = await call(req("/v1/crash", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "203.0.113.151",
      },
      body: JSON.stringify({
        sanitized_stack:
          "java.lang.IllegalStateException\n\tat io.github.popcoon.Foo.bar(Foo.kt:42)\n" +
          "\tat io.github.popcoon.Baz.qux(Baz.kt:1337)",
        app_version: "1.2.3",
        android_version: 36,
        device_model: "Google Pixel 9",
      }),
    }));
    expect(res.status).toBe(200);
  });
});
