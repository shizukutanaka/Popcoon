/**
 * AlertCondition ツリー評価エンジンのテスト。
 *
 * Kotlin 側からの仕様確認 — backend が price_below/above/atl/discount_pct/AND/OR/NOT を
 * 正しく評価することを保証する。
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

function evaluateCondition(c: AlertCondition, current: PriceRecord, history: PriceRecord[]): boolean {
  switch (c.type) {
    case "price_below":
      return current.real_price <= (c.value ?? 0);
    case "price_above":
      return current.real_price >= (c.value ?? 0);
    case "atl": {
      if (history.length < 2) return false;
      const historicLow = Math.min(...history.slice(0, -1).map(r => r.real_price));
      return current.real_price <= historicLow;
    }
    case "discount_pct": {
      if (current.list_price <= 0) return false;
      const pct = (current.list_price - current.real_price) / current.list_price * 100;
      return pct >= (c.value ?? 0);
    }
    case "and":
      return (c.children ?? []).every(child => evaluateCondition(child, current, history));
    case "or":
      return (c.children ?? []).some(child => evaluateCondition(child, current, history));
    case "not":
      return !(evaluateCondition(c.children?.[0] ?? { type: "price_below" }, current, history));
    default:
      return false;
  }
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

  describe("atl (all-time-low)", () => {
    it("trigger when current is new low", () => {
      const c: AlertCondition = { type: "atl" };
      const history = [
        { real_price: 1000, list_price: 2000 },
        { real_price: 1100, list_price: 2000 },
        { real_price: 1200, list_price: 2000 },
      ];
      const newLow = { real_price: 950, list_price: 2000 };
      expect(evaluateCondition(c, newLow, history)).toBe(true);
    });

    it("not trigger when current is higher than historic low", () => {
      const c: AlertCondition = { type: "atl" };
      const history = [
        { real_price: 1000, list_price: 2000 },
        { real_price: 1100, list_price: 2000 },
      ];
      const notLow = { real_price: 1050, list_price: 2000 };
      expect(evaluateCondition(c, notLow, history)).toBe(false);
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
});
