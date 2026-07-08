/**
 * Popcoon Backend - Cloudflare Workers
 *
 * 責務:
 *  1. 価格履歴の集約保存 (全ユーザー共有 → 予測精度向上)
 *  2. アラート条件の評価 + プッシュ通知配信
 *  3. rate limiting / 認証
 *
 * 無料枠制約:
 *  - KV: 100k read/day, 1k write/day, 1GB total
 *  - Workers: 100k req/day
 *
 * 設計:
 *  - 価格履歴は 365日分までに制限 (各keyあたり約 25KB 以内)
 *  - 全クライアントからの書き込みは rate-limited (1分5回/IP)
 *  - データ削除はクライアント要求で即時 (GDPR article 17)
 */

export interface Env {
  PRICE_HISTORY: KVNamespace;
  DEVICE_TOKENS: KVNamespace;
  ALERTS: KVNamespace;
  RATE_LIMIT: KVNamespace;
  MAX_HISTORY_PER_PRODUCT: string;
  FCM_SERVER_KEY?: string;
  ADMIN_API_KEY?: string;
  // Claude API 呼び出し用。wrangler secret put ANTHROPIC_API_KEY で設定する。
  // クライアント (Android アプリ) には一切渡さない — POST /v1/advice がこの鍵を
  // 保持したままプロキシする (APK 埋め込みは抽出可能で課金悪用リスクがあるため)。
  ANTHROPIC_API_KEY?: string;
}

interface PriceRecord {
  product_key: string;
  platform: string;
  list_price: number;
  real_price: number;
  recorded_at: string;  // ISO 8601
}

interface Alert {
  alert_id: string;
  device_token: string;
  product_key: string;
  condition: string;  // JSON of AlertCondition tree
  created_at: string;
  active: boolean;
}

// ── ユーティリティ ───────────────────────────────────────────────────────────
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": "*",
    },
  });
}

function bad(msg: string, status = 400): Response {
  return json({ error: msg }, status);
}

async function rateLimit(env: Env, ip: string, max = 5): Promise<boolean> {
  const key = `rate:${ip}:${Math.floor(Date.now() / 60000)}`;  // 1分バケット
  const raw = await env.RATE_LIMIT.get(key);
  const count = (raw ? parseInt(raw, 10) : 0) + 1;
  if (count > max) return false;
  // 90秒で消える (1分バケット + safety margin)
  await env.RATE_LIMIT.put(key, String(count), { expirationTtl: 90 });
  return true;
}

// ── ルート ──────────────────────────────────────────────────────────────────

async function getPriceHistory(env: Env, key: string): Promise<PriceRecord[]> {
  const raw = await env.PRICE_HISTORY.get(key);
  if (!raw) return [];
  try { return JSON.parse(raw) as PriceRecord[]; }
  catch { return []; }
}

async function appendPriceHistory(
  env: Env, record: PriceRecord,
): Promise<number> {
  const key = record.product_key;
  const current = await getPriceHistory(env, key);

  // 同じタイムスタンプの重複を除去
  const filtered = current.filter(r => r.recorded_at !== record.recorded_at);
  filtered.push(record);

  // 新しい順にソートして上限で切る
  filtered.sort((a, b) => b.recorded_at.localeCompare(a.recorded_at));
  const limit = parseInt(env.MAX_HISTORY_PER_PRODUCT || "365", 10);
  const trimmed = filtered.slice(0, limit);

  await env.PRICE_HISTORY.put(key, JSON.stringify(trimmed));
  return trimmed.length;
}

// ── HTTP ハンドラー ──────────────────────────────────────────────────────────

/**
 * クラッシュ payload 全体に個人情報 (メール / IPv4) が混入していないか検査する。
 * 旧実装は body.sanitized_stack だけを見ていたが、保存するのは body 全体 (JSON.stringify)
 * だったため、他フィールド (device_id 等) の PII が二重チェックをすり抜けて永続化されていた。
 * プライバシーを売りにする製品の中核 — payload 全体を走査して保守的に弾く。
 */
function containsPotentialPii(payload: unknown): boolean {
  const serialized = JSON.stringify(payload);
  const email = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
  const ipv4 = /\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/;
  return email.test(serialized) || ipv4.test(serialized);
}

/**
 * recorded_at が正準 ISO-8601 UTC ("...Z") か検証する。
 * appendPriceHistory は recorded_at を localeCompare で文字列ソート/dedup するため、
 * 非正準・不正な値を許すと履歴順序が時系列と一致せず、latest=history[0] や
 * 予測パイプライン (Holt/IQR) が壊れる。Kotlin クライアントは Instant.toString()
 * = ISO-8601 UTC を送るので、それに揃える。
 */
function isValidIsoUtc(s: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(s) && !Number.isNaN(Date.parse(s));
}

/**
 * KV.list は 1 回の呼び出しで最大 1000 キーしか返さない (list_complete=false で cursor を返す)。
 * cursor を辿って全キー名を集める。これを怠ると GDPR 削除やアラート評価が
 * 「最初の1ページ」しか処理せず、それ以降のデータを取りこぼす。
 */
async function listAllKeys(ns: KVNamespace, prefix: string): Promise<string[]> {
  const names: string[] = [];
  let cursor: string | undefined;
  do {
    const res = await ns.list({ prefix, cursor });
    for (const k of res.keys) names.push(k.name);
    cursor = res.list_complete ? undefined : res.cursor;
  } while (cursor);
  return names;
}

async function handleRequest(req: Request, env: Env): Promise<Response> {
  const url = new URL(req.url);
  const ip = req.headers.get("cf-connecting-ip") || "unknown";

  // CORS preflight
  if (req.method === "OPTIONS") {
    return new Response(null, {
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET, POST, DELETE, OPTIONS",
        "access-control-allow-headers": "content-type,x-device-token",
      },
    });
  }

  // Rate limit (read 操作は緩め)
  const isWrite = ["POST", "DELETE", "PUT"].includes(req.method);
  const allowed = await rateLimit(env, ip, isWrite ? 5 : 30);
  if (!allowed) return bad("rate limited", 429);

  // GET /v1/history?key=amazon:B0C...
  if (req.method === "GET" && url.pathname === "/v1/history") {
    const key = url.searchParams.get("key");
    if (!key) return bad("missing key");
    const records = await getPriceHistory(env, key);
    return json({ product_key: key, count: records.length, records });
  }

  // POST /v1/history  body: PriceRecord
  if (req.method === "POST" && url.pathname === "/v1/history") {
    const body = await req.json().catch(() => null) as PriceRecord | null;
    if (!body || !body.product_key || !body.recorded_at) {
      return bad("invalid payload");
    }
    // 入力検証
    if (typeof body.real_price !== "number" || body.real_price < 0) {
      return bad("real_price must be non-negative number");
    }
    if (typeof body.list_price !== "number" || body.list_price < 0) {
      return bad("list_price must be non-negative number");
    }
    if (!isValidIsoUtc(body.recorded_at)) {
      return bad("recorded_at must be ISO-8601 UTC (e.g. 2026-01-01T00:00:00Z)");
    }
    if (body.product_key.length > 200) return bad("product_key too long");

    const count = await appendPriceHistory(env, body);
    return json({ ok: true, count });
  }

  // DELETE /v1/history?key=... — GDPR article 17 対応
  if (req.method === "DELETE" && url.pathname === "/v1/history") {
    const key = url.searchParams.get("key");
    if (!key) return bad("missing key");
    await env.PRICE_HISTORY.delete(key);
    return json({ ok: true });
  }

  // POST /v1/alerts — アラート登録
  if (req.method === "POST" && url.pathname === "/v1/alerts") {
    const deviceToken = req.headers.get("x-device-token");
    if (!deviceToken) return bad("missing x-device-token");
    const body = await req.json().catch(() => null) as Alert | null;
    if (!body || !body.product_key || !body.condition) return bad("invalid payload");

    const alertId = crypto.randomUUID();
    const alert: Alert = {
      alert_id: alertId,
      device_token: deviceToken,
      product_key: body.product_key,
      condition: body.condition,
      created_at: new Date().toISOString(),
      active: true,
    };
    await env.ALERTS.put(`alert:${alertId}`, JSON.stringify(alert));
    return json({ alert_id: alertId });
  }

  // DELETE /v1/alerts/:id
  if (req.method === "DELETE" && url.pathname.startsWith("/v1/alerts/")) {
    const alertId = url.pathname.split("/").pop();
    if (!alertId) return bad("missing alert_id");
    await env.ALERTS.delete(`alert:${alertId}`);
    return json({ ok: true });
  }

  // DELETE /v1/device — 全デバイスデータ削除 (GDPR)
  if (req.method === "DELETE" && url.pathname === "/v1/device") {
    const deviceToken = req.headers.get("x-device-token");
    if (!deviceToken) return bad("missing x-device-token");
    // アラート全削除 (全ページを cursor で走査 — 1000 件超でも取りこぼさない)。
    // GDPR Article 17: 部分削除は許されないため list_complete まで辿る。
    const keys = await listAllKeys(env.ALERTS, "alert:");
    for (const name of keys) {
      const raw = await env.ALERTS.get(name);
      if (!raw) continue;
      try {
        const a = JSON.parse(raw) as Alert;
        if (a.device_token === deviceToken) await env.ALERTS.delete(name);
      } catch {}
    }
    await env.DEVICE_TOKENS.delete(deviceToken);
    return json({ ok: true });
  }

  // POST /v1/crash — 匿名クラッシュ受信
  if (req.method === "POST" && url.pathname === "/v1/crash") {
    const body = await req.json().catch(() => null) as Record<string, unknown> | null;
    if (!body) return bad("invalid payload");
    // 個人情報が含まれないことを payload 全体で確認 (保存対象は body 全体のため)。
    if (containsPotentialPii(body)) {
      return bad("payload contains potential PII");
    }
    // KV に集約 (週単位で削除、recent crashes のみ保持)
    const week = Math.floor(Date.now() / (7 * 24 * 60 * 60 * 1000));
    const key = `crash:${week}:${crypto.randomUUID()}`;
    await env.PRICE_HISTORY.put(key, JSON.stringify(body), {
      expirationTtl: 90 * 24 * 60 * 60,  // 90日で自動削除
    });
    return json({ ok: true });
  }

  // POST /v1/advice — Claude API プロキシ (AI 買い時アドバイザー)
  // Anthropic キーはこの Worker だけが保持する。クライアントはシステムプロンプトと
  // ユーザープロンプト文字列だけを送り、モデル名はここで固定する (クライアントに
  // 選ばせない — 高額モデルへの誘導や任意のパラメータ注入を防ぐ)。
  if (req.method === "POST" && url.pathname === "/v1/advice") {
    if (!env.ANTHROPIC_API_KEY) return bad("advice unavailable", 503);

    // 通常の書き込み系レート制限 (5/分/IP) とは別バケットで絞る — LLM 呼び出しは
    // 1回あたりの課金コストが history 書き込みより大きいため、より厳しい上限にする。
    const adviceAllowed = await rateLimit(env, `advice:${ip}`, 3);
    if (!adviceAllowed) return bad("rate limited", 429);

    const body = await req.json().catch(() => null) as
      { system?: string; userPrompt?: string } | null;
    if (!body?.system || !body?.userPrompt) return bad("invalid payload");
    if (body.system.length > 2000) return bad("system too long");
    if (body.userPrompt.length > 2000) return bad("userPrompt too long");

    let claudeRes: Response;
    try {
      claudeRes = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-api-key": env.ANTHROPIC_API_KEY,
          "anthropic-version": "2023-06-01",
        },
        body: JSON.stringify({
          model: "claude-sonnet-5",
          max_tokens: 200,
          system: body.system,
          messages: [{ role: "user", content: body.userPrompt }],
        }),
      });
    } catch (e) {
      console.error("advice upstream fetch failed", e);
      return bad("advice upstream unreachable", 502);
    }
    if (!claudeRes.ok) return bad("advice upstream error", 502);

    const data = await claudeRes.json().catch(() => null) as
      { content?: Array<{ type: string; text?: string }> } | null;
    const text = data?.content?.find(c => c.type === "text")?.text;
    if (!text) return bad("advice upstream empty", 502);
    return json({ advice: text });
  }

  // GET /v1/health
  if (req.method === "GET" && url.pathname === "/v1/health") {
    return json({ status: "ok", environment: "production" });
  }

  return bad("not found", 404);
}

// ── AlertCondition 評価エンジン ──────────────────────────────────────────────
interface AlertCondition {
  type: "price_below" | "price_above" | "atl" | "discount_pct" | "and" | "or" | "not";
  value?: number;
  children?: AlertCondition[];
}

function evaluateCondition(condition: AlertCondition, current: PriceRecord, history: PriceRecord[]): boolean {
  switch (condition.type) {
    case "price_below":
      return current.real_price <= (condition.value ?? 0);

    case "price_above":
      return current.real_price >= (condition.value ?? 0);

    case "atl": {
      if (history.length < 2) return false;
      // history は新しい順 (appendPriceHistory が降順ソート) で current === history[0]。
      // 過去最安は「current を除く全履歴」= history.slice(1) の最小値。
      // 旧実装は slice(0,-1) で最古を除外しており、最古が真の最安だった場合に
      // ATL を誤発火していた (= 偽の「過去最安」通知でユーザーの信頼を損なう)。
      const historicLow = Math.min(...history.slice(1).map(r => r.real_price));
      return current.real_price <= historicLow;
    }

    case "discount_pct": {
      if (current.list_price <= 0) return false;
      const pct = (current.list_price - current.real_price) / current.list_price * 100;
      return pct >= (condition.value ?? 0);
    }

    case "and":
      return (condition.children ?? []).every(c => evaluateCondition(c, current, history));

    case "or":
      return (condition.children ?? []).some(c => evaluateCondition(c, current, history));

    case "not":
      return !(evaluateCondition(condition.children?.[0] ?? { type: "price_below" }, current, history));

    default:
      return false;
  }
}

// ── FCM 通知送信 ─────────────────────────────────────────────────────────────
async function sendFcmNotification(
  fcmKey: string,
  deviceToken: string,
  title: string,
  body: string,
  data: Record<string, string>,
): Promise<boolean> {
  const payload = {
    to: deviceToken,
    notification: { title, body, sound: "default" },
    data,
    priority: "high",
  };

  const res = await fetch("https://fcm.googleapis.com/fcm/send", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `key=${fcmKey}`,
    },
    body: JSON.stringify(payload),
  });

  return res.ok;
}

// ── Scheduled: アラート評価 & 通知配信 ──────────────────────────────────────
async function evaluateAlerts(env: Env): Promise<void> {
  const fcmKey = env.FCM_SERVER_KEY;
  // 全アラートを評価する (旧実装は limit:100 で 101 件目以降を黙って無視し、
  // それらのアラートは永遠に発火しなかった)。cursor で全ページを走査。
  const keys = await listAllKeys(env.ALERTS, "alert:");

  for (const name of keys) {
    const raw = await env.ALERTS.get(name);
    if (!raw) continue;
    let alert: Alert;
    try { alert = JSON.parse(raw); } catch { continue; }
    if (!alert.active) continue;

    const history = await getPriceHistory(env, alert.product_key);
    if (history.length === 0) continue;

    const latest = history[0];

    // 条件ツリーを評価
    let condition: AlertCondition;
    try {
      condition = JSON.parse(alert.condition) as AlertCondition;
    } catch {
      // 旧形式 (単純な price_below) のフォールバック
      const target = parseFloat(alert.condition);
      if (!isNaN(target)) {
        condition = { type: "price_below", value: target };
      } else {
        continue;
      }
    }

    const triggered = evaluateCondition(condition, latest, history);
    if (!triggered) continue;

    // 通知タイトル / ボディを生成
    const discountPct = latest.list_price > 0
      ? Math.round((latest.list_price - latest.real_price) / latest.list_price * 100)
      : 0;

    const title = `価格変動: ${alert.product_key.split(":").pop()}`;
    const body = discountPct > 0
      ? `¥${latest.real_price.toLocaleString()} (${discountPct}% OFF)`
      : `¥${latest.real_price.toLocaleString()}`;

    // FCM 送信 (key が設定されている場合のみ)
    if (fcmKey && alert.device_token) {
      await sendFcmNotification(
        fcmKey,
        alert.device_token,
        title,
        body,
        {
          product_key: alert.product_key,
          real_price: String(latest.real_price),
          alert_id: alert.alert_id,
        },
      );
    }

    // 発火済みアラートを非アクティブ化 (one-shot)
    // 継続監視が必要な場合はクライアントが再登録する
    const updated: Alert = { ...alert, active: false };
    await env.ALERTS.put(name, JSON.stringify(updated));
  }
}

// ── Worker エクスポート ─────────────────────────────────────────────────────
export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      return await handleRequest(req, env);
    } catch (e) {
      console.error("unhandled", e);
      return bad("internal error", 500);
    }
  },

  async scheduled(_ev: ScheduledEvent, env: Env): Promise<void> {
    await evaluateAlerts(env);
  },
};
