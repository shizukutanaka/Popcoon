/**
 * AlertCondition ツリー評価エンジンのテスト。
 *
 * **本番の evaluateCondition を直接 import して検証する**。以前このファイルは
 * 評価器を丸ごと再実装したコピーを対象にしており (「仕様文書化」の名目)、
 * 本番コードの分岐は 1 行も通っていなかった。コピーは本番と一緒に更新されない限り
 * 乖離するし、実際に本番側にあった fail-open の欠陥
 * (空 children の and が vacuous truth で常に真、子無し not が常に真、
 *  value 無し price_above が `>= 0` で常に真) を、このコピーは同じバグごと
 * 写していたため誰も気付けなかった。
 *
 * HTTP 層・KV 実処理・認可検証は worker.test.ts が実ハンドラー越しに担う。
 */

import { describe, it, expect } from "vitest";
import { evaluateCondition, isValidCondition, MAX_CONDITION_DEPTH } from "../src/index";
import type { AlertCondition, PriceRecord } from "../src/index";

const MAX_CONDITION_JSON_LENGTH = 2000;

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

// ─────────────────────────────────────────────────────────────────────────────
// fail-open だった経路 (2026-08)
//
// evaluateCondition の深さガードには「fail-closed: 深すぎるツリーは不発火扱い」と
// 明記されているのに、壊れた条件の 3 経路は逆に **必ず発火** していた:
//   - {"type":"and"}        → children=[] に .every() で vacuous truth = true
//   - {"type":"not"}        → 既定 {type:"price_below"} (value 無し) = real_price<=0 が
//                             false → 反転して true
//   - {"type":"price_above"} → value ?? 0 で real_price >= 0 = 常に true
// どれも isValidCondition が children / value を必須にしていなかったため
// POST /v1/alerts から素通りで保存でき、毎サイクル誤通知を出し続ける
// 「壊れたアラート」を作れた。評価側・書き込み側の両方で塞ぐ。
// ─────────────────────────────────────────────────────────────────────────────
describe("壊れた条件は発火させない (fail-closed)", () => {
  const current: PriceRecord = {
    product_key: "amazon:B0", platform: "amazon",
    list_price: 5000, real_price: 4000, recorded_at: "2026-08-18T00:00:00Z",
  };
  const history: PriceRecord[] = [current, { ...current, real_price: 3000 }];

  it("children が空の and は発火しない (vacuous truth を潰す)", () => {
    expect(evaluateCondition({ type: "and", children: [] }, current, history)).toBe(false);
    expect(evaluateCondition({ type: "and" } as AlertCondition, current, history)).toBe(false);
  });

  it("children が空の or は発火しない", () => {
    expect(evaluateCondition({ type: "or", children: [] }, current, history)).toBe(false);
  });

  it("子の無い not は発火しない", () => {
    expect(evaluateCondition({ type: "not" } as AlertCondition, current, history)).toBe(false);
    expect(evaluateCondition({ type: "not", children: [] }, current, history)).toBe(false);
  });

  it("value の無い比較条件は発火しない", () => {
    for (const type of ["price_below", "price_above", "discount_pct"] as const) {
      expect(evaluateCondition({ type } as AlertCondition, current, history)).toBe(false);
    }
  });

  it("正当な条件は従来どおり発火する (締めすぎていないこと)", () => {
    expect(evaluateCondition({ type: "price_below", value: 4500 }, current, history)).toBe(true);
    expect(evaluateCondition({ type: "price_above", value: 3500 }, current, history)).toBe(true);
    expect(evaluateCondition(
      { type: "and", children: [{ type: "price_below", value: 4500 }] }, current, history,
    )).toBe(true);
    expect(evaluateCondition(
      { type: "not", children: [{ type: "price_below", value: 100 }] }, current, history,
    )).toBe(true);
  });

  it("書き込み時バリデーションも同じものを拒否する (多層防御)", () => {
    expect(isValidCondition({ type: "and" })).toBe(false);
    expect(isValidCondition({ type: "and", children: [] })).toBe(false);
    expect(isValidCondition({ type: "or", children: [] })).toBe(false);
    expect(isValidCondition({ type: "not" })).toBe(false);
    expect(isValidCondition({ type: "price_above" })).toBe(false);
    expect(isValidCondition({ type: "discount_pct" })).toBe(false);
    // not は 1 つだけ評価するので、余分な子は誤解を招くため拒否
    expect(isValidCondition({
      type: "not",
      children: [{ type: "price_below", value: 1 }, { type: "price_above", value: 2 }],
    })).toBe(false);
    // 正当なものは受理
    expect(isValidCondition({ type: "atl" })).toBe(true);
    expect(isValidCondition({ type: "price_below", value: 1000 })).toBe(true);
    expect(isValidCondition({
      type: "and", children: [{ type: "atl" }, { type: "price_below", value: 1000 }],
    })).toBe(true);
  });
});

// ── atl は ¥0 汚染レコードを最小値に混ぜない ────────────────────────────────
describe("atl の ¥0 汚染耐性", () => {
  const mk = (p: number): PriceRecord => ({
    product_key: "amazon:B0", platform: "amazon",
    list_price: 5000, real_price: p, recorded_at: "2026-08-18T00:00:00Z",
  });

  it("履歴に混ざった ¥0 が historicLow を 0 にして ATL を永久に潰さない", () => {
    // 現在 2900 は過去 (3000/3500) の最安を下回る = 本物の ATL。
    // ¥0 を最小値に含めると historicLow=0 となり、二度と発火しなくなっていた。
    const history = [mk(2900), mk(3000), mk(0), mk(3500)];
    expect(evaluateCondition({ type: "atl" }, mk(2900), history)).toBe(true);
  });

  it("有効な過去レコードが無ければ発火しない", () => {
    expect(evaluateCondition({ type: "atl" }, mk(2900), [mk(2900), mk(0)])).toBe(false);
  });

  it("現在価格が ¥0 (取得失敗) なら発火しない", () => {
    expect(evaluateCondition({ type: "atl" }, mk(0), [mk(0), mk(3000)])).toBe(false);
  });
});
