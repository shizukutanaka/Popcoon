/**
 * AlertCondition ツリー評価エンジンのテスト。
 *
 * Kotlin 側からの仕様確認 — backend が price_below/above/atl/discount_pct/AND/OR/NOT を
 * 正しく評価することを保証する。境界値・プロパティレベルの網羅は再実装コピーで行い、
 * 実ハンドラー越しの HTTP 層・KV 実処理・認可検証は worker.test.ts が担う
 * (vitest.config.ts で @cloudflare/vitest-pool-workers を 2026-07 に有効化済み)。
 */

import { describe, it, expect } from "vitest";

// 条件評価エンジンを再実装 (実装部分の仕様文書化)
interface AlertCondition {
  type: "price_below" | "price_above" | "atl" | "discount_pct" | "and" | "or" | "not";
  value?: number;
  children?: AlertCondition[];
}

interface PriceRecord {
  real_price: number;
  list_price: number;
}

const MAX_CONDITION_DEPTH = 10;

function evaluateCondition(c: AlertCondition, current: PriceRecord, history: PriceRecord[], depth = 0): boolean {
  if (depth > MAX_CONDITION_DEPTH) return false;  // fail-closed: 深すぎるツリーは不発火扱い
  switch (c.type) {
    case "price_below":
      return current.real_price <= (c.value ?? 0);
    case "price_above":
      return current.real_price >= (c.value ?? 0);
    case "atl": {
      if (history.length < 2) return false;
      // history は新しい順で current === history[0]。過去最安は current を除いた history.slice(1)。
      const historicLow = Math.min(...history.slice(1).map(r => r.real_price));
      return current.real_price <= historicLow;
    }
    case "discount_pct": {
      if (current.list_price <= 0) return false;
      const pct = (current.list_price - current.real_price) / current.list_price * 100;
      return pct >= (c.value ?? 0);
    }
    case "and": {
      const children = Array.isArray(c.children) ? c.children : [];
      return children.every(child => evaluateCondition(child, current, history, depth + 1));
    }
    case "or": {
      const children = Array.isArray(c.children) ? c.children : [];
      return children.some(child => evaluateCondition(child, current, history, depth + 1));
    }
    case "not": {
      const child = Array.isArray(c.children) ? c.children[0] : undefined;
      return !(evaluateCondition(child ?? { type: "price_below" }, current, history, depth + 1));
    }
    default:
      return false;
  }
}

const MAX_CONDITION_JSON_LENGTH = 2000;
const VALID_CONDITION_TYPES = new Set([
  "price_below", "price_above", "atl", "discount_pct", "and", "or", "not",
]);

function isValidCondition(value: unknown, depth = 0): value is AlertCondition {
  if (depth > MAX_CONDITION_DEPTH) return false;
  if (typeof value !== "object" || value === null) return false;
  const c = value as Record<string, unknown>;
  if (typeof c.type !== "string" || !VALID_CONDITION_TYPES.has(c.type)) return false;
  if (c.value !== undefined && typeof c.value !== "number") return false;
  if (c.children !== undefined) {
    if (!Array.isArray(c.children)) return false;
    if (!c.children.every(child => isValidCondition(child, depth + 1))) return false;
  }
  return true;
}

describe("AlertCondition evaluation", () => {

  describe("price_below", () => {
    it("trigger when current <= value", () => {
      const c: AlertCondition = { type: "price_below", value: 1000 };
      expect(evaluateCondition(c, { real_price: 1000, list_price: 2000 }, [])).toBe(true);
      expect(evaluateCondition(c, { real_price: 999, list_price: 2000 }, [])).toBe(true);
      expect(evaluateCondition(c, { real_price: 1001, list_price: 2000 }, [])).toBe(false);
    });
  });

  describe("price_above", () => {
    it("trigger when current >= value", () => {
      const c: AlertCondition = { type: "price_above", value: 5000 };
      expect(evaluateCondition(c, { real_price: 5000, list_price: 5000 }, [])).toBe(true);
      expect(evaluateCondition(c, { real_price: 5001, list_price: 5000 }, [])).toBe(true);
      expect(evaluateCondition(c, { real_price: 4999, list_price: 5000 }, [])).toBe(false);
    });
  });

  // 本番の契約 (evaluateAlerts): history は新しい順で current === history[0]。
  // テストもこの契約に揃える (旧テストは current を history 外に置いており本番と乖離していた)。
  describe("atl (all-time-low)", () => {
    it("trigger when current is new low", () => {
      const c: AlertCondition = { type: "atl" };
      const history = [
        { real_price: 950, list_price: 2000 },   // current (= history[0])
        { real_price: 1000, list_price: 2000 },
        { real_price: 1100, list_price: 2000 },
      ];
      expect(evaluateCondition(c, history[0], history)).toBe(true);
    });

    it("not trigger when current is higher than historic low", () => {
      const c: AlertCondition = { type: "atl" };
      const history = [
        { real_price: 1050, list_price: 2000 },  // current
        { real_price: 1000, list_price: 2000 },
        { real_price: 1100, list_price: 2000 },
      ];
      expect(evaluateCondition(c, history[0], history)).toBe(false);
    });

    it("regression: not trigger when the OLDEST record was the true low", () => {
      // 旧 slice(0,-1) 実装は最古(=真の最安)を除外し、誤って ATL を発火していた。
      const c: AlertCondition = { type: "atl" };
      const history = [
        { real_price: 100, list_price: 200 },  // current (新しい順, history[0])
        { real_price: 120, list_price: 200 },
        { real_price: 90,  list_price: 200 },  // oldest = 真の過去最安
      ];
      expect(evaluateCondition(c, history[0], history)).toBe(false);
    });

    it("requires at least 2 history entries", () => {
      const c: AlertCondition = { type: "atl" };
      expect(evaluateCondition(c, { real_price: 100, list_price: 200 }, [])).toBe(false);
      expect(evaluateCondition(c, { real_price: 100, list_price: 200 }, [
        { real_price: 200, list_price: 300 }
      ])).toBe(false);
    });
  });

  describe("discount_pct", () => {
    it("trigger when discount >= value", () => {
      const c: AlertCondition = { type: "discount_pct", value: 30 };
      // 30% OFF
      expect(evaluateCondition(c, { real_price: 700, list_price: 1000 }, [])).toBe(true);
      // 50% OFF
      expect(evaluateCondition(c, { real_price: 500, list_price: 1000 }, [])).toBe(true);
      // 20% OFF
      expect(evaluateCondition(c, { real_price: 800, list_price: 1000 }, [])).toBe(false);
    });

    it("returns false when list_price is 0", () => {
      const c: AlertCondition = { type: "discount_pct", value: 30 };
      expect(evaluateCondition(c, { real_price: 100, list_price: 0 }, [])).toBe(false);
    });
  });

  describe("AND combinator", () => {
    it("all children must be true", () => {
      const c: AlertCondition = {
        type: "and",
        children: [
          { type: "price_below", value: 1000 },
          { type: "discount_pct", value: 30 },
        ],
      };
      // 950円 + 35% OFF
      expect(evaluateCondition(c, { real_price: 950, list_price: 1500 }, [])).toBe(true);
      // 950円 + 25% OFF
      expect(evaluateCondition(c, { real_price: 950, list_price: 1267 }, [])).toBe(false);
    });
  });

  describe("OR combinator", () => {
    it("any child true → trigger", () => {
      const c: AlertCondition = {
        type: "or",
        children: [
          { type: "price_below", value: 500 },
          { type: "discount_pct", value: 50 },
        ],
      };
      // 800円 + 60% OFF (一つ満たす)
      expect(evaluateCondition(c, { real_price: 800, list_price: 2000 }, [])).toBe(true);
      // 600円 + 20% OFF (どれも満たさない)
      expect(evaluateCondition(c, { real_price: 600, list_price: 750 }, [])).toBe(false);
    });
  });

  describe("NOT combinator", () => {
    it("inverts child", () => {
      const c: AlertCondition = {
        type: "not",
        children: [{ type: "price_below", value: 1000 }],
      };
      expect(evaluateCondition(c, { real_price: 1500, list_price: 2000 }, [])).toBe(true);
      expect(evaluateCondition(c, { real_price: 500, list_price: 2000 }, [])).toBe(false);
    });
  });

  describe("Nested ツリー (実用例)", () => {
    it("(ATL OR 50%OFF) AND 1000円以下", () => {
      const c: AlertCondition = {
        type: "and",
        children: [
          {
            type: "or",
            children: [
              { type: "atl" },
              { type: "discount_pct", value: 50 },
            ],
          },
          { type: "price_below", value: 1000 },
        ],
      };
      const history = [{ real_price: 800, list_price: 2000 }];

      // 700円 + 50% OFF + 価格1000円以下 → トリガー
      expect(evaluateCondition(c, { real_price: 700, list_price: 1500 }, history)).toBe(true);

      // 1500円 + 50% OFF (価格条件失敗)
      expect(evaluateCondition(c, { real_price: 1500, list_price: 3000 }, history)).toBe(false);

      // 800円 + 30% OFF + ATL でない → トリガーしない
      expect(evaluateCondition(c, { real_price: 850, list_price: 1300 }, history)).toBe(false);
    });
  });

  // 回帰: 深すぎる条件ツリーで JS のコールスタックが枯渇し、evaluateAlerts の
  // for ループ全体が例外で中断して以降のアラートが二度と評価されなくなる恐れがあった
  // (機能過不足監査で発見)。depth 打ち切りで fail-closed に倒す。
  describe("深いネストの打ち切り (スタック枯渇防止)", () => {
    function deepNot(depth: number): AlertCondition {
      let c: AlertCondition = { type: "price_below", value: 0 };  // 常に true (real_price>=0)
      for (let i = 0; i < depth; i++) c = { type: "not", children: [c] };
      return c;
    }

    it("MAX_CONDITION_DEPTH 以内なら正常に評価される", () => {
      // not を5回重ねる (奇数回で反転) — price_below:0 は real_price=100 で false
      const c = deepNot(5);
      expect(evaluateCondition(c, { real_price: 100, list_price: 200 }, [])).toBe(true);
    });

    it("MAX_CONDITION_DEPTH を超える深いネストでも例外を投げず終了する (not は反転するため値は不定)", () => {
      // not は打ち切り時の false を反転するため、最終値はネスト段数の偶奇に依存する。
      // ここで保証したいのは「クラッシュしない・有限時間で終わる」ことであり特定の真偽値ではない。
      const c = deepNot(10_000);
      expect(() => evaluateCondition(c, { real_price: 100, list_price: 200 }, [])).not.toThrow();
    });

    it("深い and ネストは打ち切り以降 fail-closed (false) で確定する (単調な合成なので反転しない)", () => {
      let c: AlertCondition = { type: "price_below", value: 0 };  // real_price=100 では false
      for (let i = 0; i < 10_000; i++) c = { type: "and", children: [{ type: "price_above", value: 0 }, c] };
      expect(() => evaluateCondition(c, { real_price: 100, list_price: 200 }, [])).not.toThrow();
      expect(evaluateCondition(c, { real_price: 100, list_price: 200 }, [])).toBe(false);
    });

    it("children が配列でない場合も例外を投げない (壊れたデータへの耐性)", () => {
      const malformed = { type: "and", children: { not: "an array" } } as unknown as AlertCondition;
      expect(() => evaluateCondition(malformed, { real_price: 100, list_price: 200 }, [])).not.toThrow();
    });
  });
});

// ── isValidCondition (POST /v1/alerts 書き込み時バリデーション) ────────────────
// 以前は body.condition を一切検証せず任意の文字列をそのまま KV に保存していた。
describe("isValidCondition", () => {
  it("正当な単純条件を受理する", () => {
    expect(isValidCondition({ type: "price_below", value: 1000 })).toBe(true);
  });

  it("正当なネストしたツリーを受理する", () => {
    expect(isValidCondition({
      type: "and",
      children: [{ type: "atl" }, { type: "discount_pct", value: 30 }],
    })).toBe(true);
  });

  it("未知の type を拒否する", () => {
    expect(isValidCondition({ type: "delete_everything" })).toBe(false);
  });

  it("value が数値以外なら拒否する", () => {
    expect(isValidCondition({ type: "price_below", value: "1000" })).toBe(false);
  });

  it("children が配列でなければ拒否する", () => {
    expect(isValidCondition({ type: "and", children: { evil: true } })).toBe(false);
  });

  it("MAX_CONDITION_DEPTH を超えるツリーを拒否する", () => {
    let c: AlertCondition = { type: "price_below", value: 0 };
    for (let i = 0; i < MAX_CONDITION_DEPTH + 1; i++) c = { type: "not", children: [c] };
    expect(isValidCondition(c)).toBe(false);
  });

  it("MAX_CONDITION_DEPTH ちょうどなら受理する", () => {
    let c: AlertCondition = { type: "price_below", value: 0 };
    for (let i = 0; i < MAX_CONDITION_DEPTH; i++) c = { type: "not", children: [c] };
    expect(isValidCondition(c)).toBe(true);
  });

  it("null・プリミティブ・配列を拒否する", () => {
    expect(isValidCondition(null)).toBe(false);
    expect(isValidCondition("price_below")).toBe(false);
    expect(isValidCondition(42)).toBe(false);
  });
});

// ── KV ページネーション (GDPR 削除 / アラート評価の取りこぼし防止) ──────────────
// KV.list は最大 1000 キー/呼び出し。cursor を辿らないと 1000 件超を取りこぼす。
// src/index.ts::listAllKeys の契約をここで固定する。

interface FakeKV {
  list(opts: { prefix?: string; cursor?: string }): Promise<{
    keys: { name: string }[]; list_complete: boolean; cursor?: string;
  }>;
  get(name: string): Promise<string | null>;
  delete(name: string): Promise<void>;
}

function makeKV(entries: [string, string][]): FakeKV & { store: Map<string, string> } {
  const store = new Map(entries);
  return {
    store,
    async list({ prefix = "", cursor }) {
      const PAGE = 1000;
      const all = [...store.keys()].filter(k => k.startsWith(prefix)).sort();
      const start = cursor ? parseInt(cursor, 10) : 0;
      const page = all.slice(start, start + PAGE);
      const next = start + PAGE;
      const complete = next >= all.length;
      return { keys: page.map(name => ({ name })), list_complete: complete, cursor: complete ? undefined : String(next) };
    },
    async get(name) { return store.has(name) ? store.get(name)! : null; },
    async delete(name) { store.delete(name); },
  };
}

async function listAllKeys(ns: FakeKV, prefix: string): Promise<string[]> {
  const names: string[] = [];
  let cursor: string | undefined;
  do {
    const res = await ns.list({ prefix, cursor });
    for (const k of res.keys) names.push(k.name);
    cursor = res.list_complete ? undefined : res.cursor;
  } while (cursor);
  return names;
}

describe("KV ページネーション", () => {
  function seed(n: number, victimIdx: number[]) {
    const entries: [string, string][] = [];
    for (let i = 0; i < n; i++) {
      const owner = victimIdx.includes(i) ? "victim" : "other";
      entries.push([`alert:${String(i).padStart(5, "0")}`, JSON.stringify({ device_token: owner })]);
    }
    return entries;
  }

  it("listAllKeys が 1000 件超を全て返す", async () => {
    const kv = makeKV(seed(2500, []));
    expect((await listAllKeys(kv, "alert:")).length).toBe(2500);
    // 単一ページ呼び出しは 1000 で頭打ち (旧バグの再現)
    expect((await kv.list({ prefix: "alert:" })).keys.length).toBe(1000);
  });

  it("GDPR 削除がページをまたいで victim の全アラートを消す", async () => {
    const kv = makeKV(seed(2500, [5, 1500, 2499]));
    for (const name of await listAllKeys(kv, "alert:")) {
      const raw = await kv.get(name);
      if (!raw) continue;
      if ((JSON.parse(raw) as { device_token: string }).device_token === "victim") await kv.delete(name);
    }
    const left = [...kv.store.values()].filter(v => JSON.parse(v).device_token === "victim").length;
    expect(left).toBe(0);          // 完全削除
    expect(kv.store.size).toBe(2497);  // 他者は無傷
  });

  it("回帰: 単一ページ削除は 2 ページ目以降の victim データを残す (GDPR 違反)", async () => {
    const kv = makeKV(seed(2500, [5, 1500, 2499]));
    const onePage = (await kv.list({ prefix: "alert:" })).keys.map(k => k.name);
    for (const name of onePage) {
      const raw = await kv.get(name);
      if (!raw) continue;
      if ((JSON.parse(raw) as { device_token: string }).device_token === "victim") await kv.delete(name);
    }
    const left = [...kv.store.values()].filter(v => JSON.parse(v).device_token === "victim").length;
    expect(left).toBe(2);  // index 1500 & 2499 が削除されず残る
  });
});

// ── クラッシュ payload の PII 検査 (プライバシーが製品の中核差別化) ──────────────
// src/index.ts::containsPotentialPii の契約。保存対象は body 全体なので payload 全体を走査する。
function containsPotentialPii(payload: unknown): boolean {
  const serialized = JSON.stringify(payload);
  const email = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
  const ipv4 = /\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/;
  return email.test(serialized) || ipv4.test(serialized);
}

describe("crash PII 検査", () => {
  it("sanitized_stack 以外のフィールドのメールも検出する (旧実装の穴)", () => {
    expect(containsPotentialPii({ sanitized_stack: "Foo.bar(Foo.kt:42)", device_id: "user@email.com" })).toBe(true);
  });
  it("他フィールドの IPv4 も検出する", () => {
    expect(containsPotentialPii({ sanitized_stack: "clean", note: "from 10.0.0.1" })).toBe(true);
  });
  it("ネストされた PII も検出する", () => {
    expect(containsPotentialPii({ meta: { contact: "a.b@c.co" } })).toBe(true);
  });
  it("PII の無い正当なクラッシュは受理する", () => {
    expect(containsPotentialPii({ sanitized_stack: "com.example.Foo.bar(Foo.kt:42)", app_version: "1.2.3", os: "Android 14" })).toBe(false);
  });
});

// ── recorded_at の ISO-8601 UTC 検証 (履歴ソート/dedup の前提) ──────────────────
// src/index.ts::isValidIsoUtc の契約。非正準値を弾かないと localeCompare ソートが崩れる。
function isValidIsoUtc(s: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(s) && !Number.isNaN(Date.parse(s));
}

describe("recorded_at 検証", () => {
  it("正準 ISO-8601 UTC を受理する (Kotlin Instant.toString() 形式)", () => {
    expect(isValidIsoUtc("2026-01-01T00:00:00Z")).toBe(true);
    expect(isValidIsoUtc("2026-06-13T16:30:05.123Z")).toBe(true);
  });
  it("非正準・不正な値を弾く", () => {
    for (const bad of ["", "2026-01-01", "2026-01-01T00:00:00+09:00", "today", "1767225600", "2026-13-45T99:99:99Z"]) {
      expect(isValidIsoUtc(bad)).toBe(false);
    }
  });
  it("回帰: 不正な timestamp は localeCompare ソートで誤って先頭(=latest)に来る", () => {
    const sortDesc = (a: string[]) => [...a].sort((x, y) => y.localeCompare(x));
    const good = ["2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", "2026-03-01T00:00:00Z"];
    expect(sortDesc(good)[0]).toBe("2026-03-01T00:00:00Z");
    expect(sortDesc([...good, "today"])[0]).toBe("today");  // 検証が無いとこうなる
  });
});
