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
  // Workers Rate Limiting binding (2025-09 GA)。wrangler.toml の [[ratelimits]] で設定。
  // KV カウンター実装 (rateLimit() のフォールバック) は read-modify-write レースで
  // バースト時に上限を超えて通す欠陥がある上、リクエスト毎に KV read+write を消費する
  // (無料枠 100k read/1k write/day を圧迫)。binding は per-PoP の近似カウンターだが
  // アトミックで KV を消費しない。未設定 (undefined) の場合は従来の KV パスに落ちる —
  // 既存デプロイを壊さないための漸進移行。
  WRITE_RATE_LIMITER?: RateLimit;
  READ_RATE_LIMITER?: RateLimit;
  ADVICE_RATE_LIMITER?: RateLimit;
}

export interface PriceRecord {
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

// POST /v1/crash の受理上限 (バイト概算、JSON.stringify().length で判定)。クライアント
// (PrivacyCrashReporter.kt) は sanitized_stack を 8000 文字に切り詰めて送るため、
// 他のメタデータを含めても正当なペイロードはこれで十分。
const MAX_CRASH_PAYLOAD_BYTES = 16_384;

// アラート KV の TTL。以前は無期限で、アプリを再インストール/放棄したデバイスの
// アラートも永遠に残り続け、毎時 cron (evaluateAlerts) が listAllKeys で全件を
// 走査するたびに無駄な KV read を消費し続けていた (機能過不足監査で発見)。
// - ACTIVE (未発火): 半年間何のアクションもなければ放棄デバイスとみなして良い長さ。
//   ユーザーが引き続き使っていれば再登録等で自然に更新される想定。
// - FIRED (発火済み・one-shot で active=false): 二度と発火しないため短い猶予のみ残す。
const ALERT_TTL_ACTIVE_SECONDS = 180 * 24 * 60 * 60;
const ALERT_TTL_FIRED_SECONDS = 30 * 24 * 60 * 60;

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

/**
 * タイミングセーフな文字列比較。管理キー等の秘密値の照合に使う。
 * 通常の `!==` は不一致箇所までの時間で長さ/内容が漏れうる (機能過不足監査で発見)。
 * Node の `crypto.timingSafeEqual` は同じ長さのバッファしか受け付けないため、
 * 長さ不一致の早期リターンもタイミング漏洩源になる — ここでは長さが違っても
 * 常に一定回数の XOR 累積を行ってから false を返す (Workers ランタイムに依存しない
 * 素朴な実装、nodejs_compat が無くても動く)。
 */
function timingSafeEqual(a: string, b: string): boolean {
  const bufA = new TextEncoder().encode(a);
  const bufB = new TextEncoder().encode(b);
  const len = Math.max(bufA.length, bufB.length);
  let diff = bufA.length ^ bufB.length;
  for (let i = 0; i < len; i++) {
    diff |= (bufA[i] ?? 0) ^ (bufB[i] ?? 0);
  }
  return diff === 0;
}

/**
 * レート制限。ネイティブ binding (per-PoP・アトミック・KV 消費なし) があればそれを使い、
 * 無ければ従来の KV 1分バケットカウンター (近似・レースあり) にフォールバックする。
 * binding 側の limit/period は wrangler.toml の [[ratelimits]] で宣言するため、
 * `max` はフォールバック時のみ意味を持つ。
 */
async function rateLimit(
  env: Env, ip: string, max = 5, binding?: RateLimit,
): Promise<boolean> {
  if (binding) {
    const { success } = await binding.limit({ key: ip });
    return success;
  }
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
/**
 * クライアント (PrivacyCrashReporter.sanitizeStack) と **同一の除去パターン**。
 *
 * サーバー側の役割は「二重チェック」= 古い/改造されたクライアントや、
 * クライアント側サニタイズの取りこぼしを受け取らないこと。以前ここは
 * **email と IPv4 の 2 種類しか見ておらず**、クライアントが除去している 9 種類のうち
 * 電話番号・Authorization ヘッダ・API キー/トークン/パスワード・AWS アクセスキー・
 * 端末内ユーザーパス・URL クエリの 7 種類が素通りしていた。
 * 二重チェックを名乗る以上、対象はクライアントと揃っていなければならない。
 *
 * 各パターンは **冪等** であることが要件: 既にサニタイズ済みのペイロード
 * (`[redacted]` / `[tel]` / `[email]` 等) に再適用しても文字列が変化しない。
 * これにより「サニタイズしても変わらない = PII を含まない」と判定でき、
 * 正規のクライアントからの正当なレポートを誤って拒否しない。
 */
function sanitizePii(text: string): string {
  return text
    // メールアドレス
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[email]")
    // URL クエリパラメータ
    .replace(/([?&][^=\s&#]+=)[^\s&#"')]+/g, "$1[redacted]")
    // AWS アクセスキー ID
    .replace(/AKIA[0-9A-Z]{16}/g, "[aws-key]")
    // Authorization ヘッダ (任意スキーム)
    .replace(/(authorization\s*[:=]\s*)(?:\w+\s+)?[^\s"',;]+/gi, "$1[redacted]")
    // api_key / secret / token / password / credential の値
    // 開き引用符は **キャプチャ側に含める**。`["']?` を capture の外に置くと
    // `api_key="secret"` → `api_key=[redacted]"` と開き引用符が消え、
    // その出力に再適用すると更に変化してしまう (冪等でない)。冪等でないと
    // 「サニタイズ済みのペイロード」を PII 有りと誤判定して正当なレポートを全拒否する。
    .replace(
      /("?\w*(?:api[_-]?key|secret|token|password|credential)\w*"?\s*[:=]\s*["']?)[^\s"',&}]+/gi,
      "$1[redacted]",
    )
    // IPv4
    .replace(/\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/g, "[ip]")
    // 電話番号 (日本 国際/国内)
    .replace(/\b\+?81[-\s]?\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b/g, "[tel]")
    .replace(/\b0\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b/g, "[tel]")
    // Android ファイルパスのユーザー名部分
    .replace(/\/data\/user\/0\/[^/]+\/files\/[^/\s]+/g, "/data/user/0/[pkg]/files/[user]")
    .replace(/\/storage\/emulated\/\d+\/[^/\s]+/g, "/storage/emulated/[u]/[user]");
}

/**
 * ペイロード内の全ての文字列値を列挙する (ネストしたオブジェクト・配列も辿る)。
 * 保存対象は body 全体なので、どの階層に入っていても見る。
 */
function* stringValues(node: unknown): Generator<string> {
  if (typeof node === "string") {
    yield node;
  } else if (Array.isArray(node)) {
    for (const v of node) yield* stringValues(v);
  } else if (node !== null && typeof node === "object") {
    for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
      yield k;
      yield* stringValues(v);
    }
  }
}

/**
 * ペイロードに PII らしきものが残っていないか。
 * 各文字列値をサニタイズして変化すれば、除去対象が含まれていたということ。
 *
 * **JSON.stringify した 1 本の文字列ではなく、値を 1 つずつ見る**のが要点。
 * シリアライズすると値の中の `"` が `\"` に変わり、`api_key="..."` のような
 * 引用符を含むパターンが崩れて誤判定する (実際、クライアントがサニタイズ済みの
 * 正当なペイロードを 400 で拒否した)。クライアント側 (sanitizeStack) も
 * 生のスタックトレース文字列に対して適用しており、こちらが揃った形になる。
 */
export function containsPotentialPii(payload: unknown): boolean {
  for (const s of stringValues(payload)) {
    if (sanitizePii(s) !== s) return true;
  }
  return false;
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
        "access-control-allow-headers": "content-type,x-device-token,x-admin-key",
      },
    });
  }

  // Rate limit (read 操作は緩め)
  const isWrite = ["POST", "DELETE", "PUT"].includes(req.method);
  const allowed = await rateLimit(
    env, ip, isWrite ? 5 : 30,
    isWrite ? env.WRITE_RATE_LIMITER : env.READ_RATE_LIMITER,
  );
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
    // **0 は「無料商品」ではなく取得失敗の痕跡** として拒否する (2026-08)。
    // クライアントの FallbackScraper は price が取れないとき realPrice=0 の Product を
    // 捏造していた (cdf61dc で修正)。0 が履歴に入ると BuyTimingScorer の
    // 「過去最安値到達」判定が壊れる (実測: 95/BUY_NOW → 40/NEUTRAL に反転) ため、
    // 読み出し側の防御と合わせて書き込み側でも弾く (多層防御)。
    // クライアント側 parsePriceToLong も同じ規約 (<=0 は null = 失敗) で揃えてある。
    if (typeof body.real_price !== "number" || body.real_price <= 0) {
      return bad("real_price must be a positive number");
    }
    if (typeof body.list_price !== "number" || body.list_price < 0) {
      return bad("list_price must be non-negative number");
    }
    if (!isValidIsoUtc(body.recorded_at)) {
      return bad("recorded_at must be ISO-8601 UTC (e.g. 2026-01-01T00:00:00Z)");
    }
    if (body.product_key.length > 200) return bad("product_key too long");
    // platform は無制限文字列だったため、無効なゴミ値を大量投入すれば無料枠の KV 容量
    // (1GB) を消費できた。想定値は "amazon"/"rakuten"/"yahoo" 程度の短い識別子。
    if (typeof body.platform !== "string" || body.platform.length > 50) {
      return bad("platform must be a short string");
    }

    const count = await appendPriceHistory(env, body);
    return json({ ok: true, count });
  }

  // DELETE /v1/history?key=... — 管理用データ削除 (要 x-admin-key)。
  // PriceRecord (product_key/platform/price/recorded_at) は個人を一切紐付けない
  // 全ユーザー共有データのため、実際には GDPR Article 17 (個人データ削除権) の対象では
  // ない。にもかかわらず以前は誰でも product_key さえ知っていれば (ASIN 等は商品ページから
  // 公開情報として推測可能) 匿名で任意商品の共有価格履歴を消せる、認証なしのエンドポイント
  // だった — 悪用すれば予測機能の基盤である crowd-sourced データセットを破壊できる
  // (機能過不足監査で発見)。ADMIN_API_KEY は元々 Env/wrangler.toml に用意されていたが
  // どこからも参照されていなかった。
  if (req.method === "DELETE" && url.pathname === "/v1/history") {
    if (!env.ADMIN_API_KEY) return bad("admin operations unavailable", 503);
    if (!timingSafeEqual(req.headers.get("x-admin-key") ?? "", env.ADMIN_API_KEY)) {
      return bad("forbidden", 403);
    }
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
    if (body.product_key.length > 200) return bad("product_key too long");
    if (body.condition.length > MAX_CONDITION_JSON_LENGTH) return bad("condition too long");
    // 深いネスト・壊れた構造の条件ツリーは保存しない (以前は無検証で任意の文字列を保存していた)。
    const parsedCondition = (() => { try { return JSON.parse(body.condition); } catch { return null; } })();
    if (!isValidCondition(parsedCondition)) return bad("invalid condition tree");

    const alertId = crypto.randomUUID();
    const alert: Alert = {
      alert_id: alertId,
      device_token: deviceToken,
      product_key: body.product_key,
      condition: body.condition,
      created_at: new Date().toISOString(),
      active: true,
    };
    await env.ALERTS.put(`alert:${alertId}`, JSON.stringify(alert), {
      expirationTtl: ALERT_TTL_ACTIVE_SECONDS,
    });
    return json({ alert_id: alertId });
  }

  // DELETE /v1/alerts/:id — 所有デバイスのみ削除可能。
  // 以前は alert_id (UUID) さえ知っていれば誰でも他人のアラートを削除できた
  // (機能過不足監査で発見: UUID の推測は現実的ではないが、URL 共有やログ露出等で
  // ID が漏れた場合に無認可の削除ができてしまう設計上の欠陥だった)。
  if (req.method === "DELETE" && url.pathname.startsWith("/v1/alerts/")) {
    const deviceToken = req.headers.get("x-device-token");
    if (!deviceToken) return bad("missing x-device-token");
    const alertId = url.pathname.split("/").pop();
    if (!alertId) return bad("missing alert_id");
    const key = `alert:${alertId}`;
    const raw = await env.ALERTS.get(key);
    if (!raw) return json({ ok: true });  // 既に存在しない = 冪等に成功扱い
    const existing = (() => { try { return JSON.parse(raw) as Alert; } catch { return null; } })();
    if (existing?.device_token !== deviceToken) return bad("forbidden", 403);
    await env.ALERTS.delete(key);
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
    // 他の書き込みルート (product_key/condition/platform 等) は個々のフィールド長を
    // 検証していたが、この経路だけはペイロード全体のサイズ上限が一切無かった。
    // クライアント (PrivacyCrashReporter.kt) は sanitized_stack を 8000 文字に切り詰めて
    // 送るため正当なペイロードは十分小さいが、サーバー側には何の強制も無く、認証も無い
    // (匿名クラッシュ受信の設計上必須)。任意の巨大な JSON を送り続けられ、しかも
    // 保存先が価格履歴/予測機能と同じ PRICE_HISTORY KV 名前空間 (無料枠 1GB 上限) のため、
    // 無認証のストレージ枯渇攻撃になり得た (機能過不足監査で発見)。
    // Content-Length ヘッダーがあれば req.json() でボディ全体をバッファする前に早期拒否する
    // (chunked 転送等でヘッダーが無い/信頼できないケースは後段のシリアライズ後サイズ検査で拾う)。
    const declaredLength = parseInt(req.headers.get("content-length") ?? "", 10);
    if (!isNaN(declaredLength) && declaredLength > MAX_CRASH_PAYLOAD_BYTES) {
      return bad("payload too large", 413);
    }
    const body = await req.json().catch(() => null) as Record<string, unknown> | null;
    if (!body) return bad("invalid payload");
    if (JSON.stringify(body).length > MAX_CRASH_PAYLOAD_BYTES) {
      return bad("payload too large", 413);
    }
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
    const adviceAllowed = await rateLimit(env, `advice:${ip}`, 3, env.ADVICE_RATE_LIMITER);
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
          // 100文字程度の買い物助言には Haiku で十分。Sonnet の約 1/3 のコスト
          // ($1/$5 per MTok) で、無料枠運用のプロキシとして運用費を大幅に抑える
          // (2026-07 リサーチ: claude-haiku-4-5 が現行の推奨低コストモデル)。
          // プロンプトキャッシュは使わない — Haiku 4.5 の最小キャッシュ長は 4096 トークンで、
          // 本 system プロンプト (数百トークン) はそれに満たず無効になるため。
          model: "claude-haiku-4-5",
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
export interface AlertCondition {
  type: "price_below" | "price_above" | "atl" | "discount_pct" | "and" | "or" | "not";
  value?: number;
  children?: AlertCondition[];
}

// 条件ツリーの最大ネスト深度。POST /v1/alerts での書き込み時バリデーション (isValidCondition)
// と evaluateCondition の再帰打ち切りの両方で使う。書き込み時バリデーションだけでは
// このガード導入以前に KV へ既に保存済みの不正なツリーを防げないため、評価時にも
// 独立して深さを打ち切る (多層防御 — 機能過不足監査で発見: 深いネストで JS のコールスタックが
// 枯渇すると evaluateAlerts の for ループ全体が例外で中断し、それ以降のアラートが
// 二度と評価されなくなる恐れがあった)。
export const MAX_CONDITION_DEPTH = 10;

export function evaluateCondition(
  condition: AlertCondition, current: PriceRecord, history: PriceRecord[], depth = 0,
): boolean {
  if (depth > MAX_CONDITION_DEPTH) return false;  // fail-closed: 深すぎるツリーは不発火扱い

  // 比較系は閾値が無ければ発火させない。`condition.value ?? 0` で代用すると
  // price_above が `real_price >= 0` となり **常に発火** する (fail-open)。
  const threshold = typeof condition.value === "number" ? condition.value : null;
  // and/or/not の子。空なら発火させない (下記の理由)。
  const children = Array.isArray(condition.children) ? condition.children : [];

  switch (condition.type) {
    case "price_below":
      return threshold !== null && current.real_price <= threshold;

    case "price_above":
      return threshold !== null && current.real_price >= threshold;

    case "atl": {
      // real_price <= 0 は取得失敗を 0 円として記録した汚染レコードで、実際に成立した
      // 価格ではない。最小値に混ざると historicLow が 0 になり、**本物の過去最安に
      // 到達しても永久に発火しない**。書き込み側は塞いだが既存レコードは残るため、
      // 評価側でも無視する (アプリ側の読み出し防御と同じ方針)。
      const past = history.slice(1).map(r => r.real_price).filter(p => p > 0);
      if (past.length === 0 || current.real_price <= 0) return false;
      return current.real_price <= Math.min(...past);
    }

    case "discount_pct": {
      if (threshold === null) return false;
      if (current.list_price <= 0) return false;
      const pct = (current.list_price - current.real_price) / current.list_price * 100;
      return pct >= threshold;
    }

    // 空の children で `every` は **真** を返す (vacuous truth) ため、
    // `{"type":"and"}` だけのアラートが毎サイクル無条件に発火していた。
    case "and":
      return children.length > 0 &&
        children.every(c => evaluateCondition(c, current, history, depth + 1));

    case "or":
      return children.some(c => evaluateCondition(c, current, history, depth + 1));

    // 子が無い `not` は以前 `{ type: "price_below" }` (= value 無し = real_price <= 0)
    // を既定にしており、正常な価格なら false → 反転して **常に発火** していた。
    case "not":
      return children.length > 0 &&
        !evaluateCondition(children[0], current, history, depth + 1);

    default:
      return false;
  }
}

const MAX_CONDITION_JSON_LENGTH = 2000;
const VALID_CONDITION_TYPES = new Set([
  "price_below", "price_above", "atl", "discount_pct", "and", "or", "not",
]);

/**
 * POST /v1/alerts 書き込み時のツリー構造バリデーション。壊れた/悪意ある条件を
 * KV に保存させない (以前は body.condition を JSON として一切検証せず、任意の
 * 文字列がそのまま保存されていた — 機能過不足監査で発見)。
 */
export function isValidCondition(value: unknown, depth = 0): value is AlertCondition {
  if (depth > MAX_CONDITION_DEPTH) return false;
  if (typeof value !== "object" || value === null) return false;
  const c = value as Record<string, unknown>;
  if (typeof c.type !== "string" || !VALID_CONDITION_TYPES.has(c.type)) return false;
  if (c.value !== undefined && typeof c.value !== "number") return false;
  if (c.children !== undefined) {
    if (!Array.isArray(c.children)) return false;
    if (!c.children.every(child => isValidCondition(child, depth + 1))) return false;
  }
  // 型ごとの必須項目。以前はここが無く、`{"type":"and"}` や `{"type":"price_above"}` が
  // そのまま保存できてしまい、評価側で毎サイクル無条件に発火する「壊れたアラート」が
  // 作れた。深さ制限と同じく**書き込み時と評価時の両方**で塞ぐ (評価側だけでは
  // このガード導入前に保存済みのものを防げず、書き込み側だけでは KV の既存分を防げない)。
  const childCount = Array.isArray(c.children) ? c.children.length : 0;
  switch (c.type) {
    case "and":
    case "or":
    case "not":
      // not は 1 つだけ評価するので余分な子は誤解を招く
      if (childCount === 0) return false;
      if (c.type === "not" && childCount !== 1) return false;
      return true;
    case "price_below":
    case "price_above":
    case "discount_pct":
      return typeof c.value === "number" && Number.isFinite(c.value);
    default:
      // atl は閾値も子も取らない
      return true;
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
    // 1 件のアラート処理で予期しない例外 (壊れたデータ、深すぎる条件ツリー、FCM 障害等) が
    // 起きても for ループ全体を止めない。以前は無防備で、1 件の例外が evaluateAlerts() を
    // 丸ごと中断させ、それ以降 (KV.list の反復順で後にある) 全アラートがそのタイマー実行で
    // 一切評価されなくなっていた。cron は1時間毎に再実行されるとはいえ、同じ壊れたアラートが
    // 毎回同じ位置で例外を起こせば恒久的にそれ以降のアラートが発火しなくなる
    // (機能過不足監査で発見)。
    try {
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
      // 二度と発火しないレコードなので TTL を短縮し、無期限の KV 滞留を防ぐ
      // (機能過不足監査で発見)。
      const updated: Alert = { ...alert, active: false };
      await env.ALERTS.put(name, JSON.stringify(updated), {
        expirationTtl: ALERT_TTL_FIRED_SECONDS,
      });
    } catch (e) {
      console.error(`evaluateAlerts: skipping ${name} after error`, e);
    }
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
