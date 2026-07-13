/**
 * rateLimit() の binding/KV フォールバック選択ロジックのテスト。
 *
 * src/index.ts はこのバケットの vitest-pool-workers 設定を持たないため
 * (alerts.test.ts と同じ制約)、実装をここに再実装して契約として固定する。
 * ネイティブ RateLimit binding (2025-09 GA) があればそれを使い、無ければ
 * 従来の KV 1分バケットカウンターに落ちる。
 */

import { describe, it, expect } from "vitest";

interface RateLimitBinding {
  limit(opts: { key: string }): Promise<{ success: boolean }>;
}

interface FakeKV {
  get(key: string): Promise<string | null>;
  put(key: string, value: string, opts?: { expirationTtl?: number }): Promise<void>;
}

function makeKV(): FakeKV & { store: Map<string, string>; reads: number; writes: number } {
  const store = new Map<string, string>();
  const kv = {
    store,
    reads: 0,
    writes: 0,
    async get(key: string) { kv.reads++; return store.get(key) ?? null; },
    async put(key: string, value: string) { kv.writes++; store.set(key, value); },
  };
  return kv;
}

// src/index.ts::rateLimit の契約を再実装 (Date.now は now 引数化してテスト決定化)
async function rateLimit(
  kv: FakeKV, ip: string, max: number, binding: RateLimitBinding | undefined, now: number,
): Promise<boolean> {
  if (binding) {
    const { success } = await binding.limit({ key: ip });
    return success;
  }
  const key = `rate:${ip}:${Math.floor(now / 60000)}`;
  const raw = await kv.get(key);
  const count = (raw ? parseInt(raw, 10) : 0) + 1;
  if (count > max) return false;
  await kv.put(key, String(count), { expirationTtl: 90 });
  return true;
}

describe("rateLimit binding/KV フォールバック", () => {
  const NOW = 1_750_000_000_000;

  it("binding があれば binding の判定を使い、KV には一切触れない", async () => {
    const kv = makeKV();
    const denyBinding: RateLimitBinding = { limit: async () => ({ success: false }) };
    const allowBinding: RateLimitBinding = { limit: async () => ({ success: true }) };

    expect(await rateLimit(kv, "1.2.3.4", 5, denyBinding, NOW)).toBe(false);
    expect(await rateLimit(kv, "1.2.3.4", 5, allowBinding, NOW)).toBe(true);
    expect(kv.reads).toBe(0);
    expect(kv.writes).toBe(0);
  });

  it("binding が無ければ KV カウンターで max 回まで許可し max+1 回目を拒否する", async () => {
    const kv = makeKV();
    for (let i = 0; i < 5; i++) {
      expect(await rateLimit(kv, "1.2.3.4", 5, undefined, NOW)).toBe(true);
    }
    expect(await rateLimit(kv, "1.2.3.4", 5, undefined, NOW)).toBe(false);
  });

  it("KV フォールバックは IP 毎に独立してカウントする", async () => {
    const kv = makeKV();
    for (let i = 0; i < 5; i++) await rateLimit(kv, "1.1.1.1", 5, undefined, NOW);
    expect(await rateLimit(kv, "1.1.1.1", 5, undefined, NOW)).toBe(false);
    expect(await rateLimit(kv, "2.2.2.2", 5, undefined, NOW)).toBe(true);
  });

  it("KV フォールバックは分バケットが変わればリセットされる", async () => {
    const kv = makeKV();
    for (let i = 0; i < 6; i++) await rateLimit(kv, "1.2.3.4", 5, undefined, NOW);
    expect(await rateLimit(kv, "1.2.3.4", 5, undefined, NOW)).toBe(false);
    // 次の1分バケット
    expect(await rateLimit(kv, "1.2.3.4", 5, undefined, NOW + 60_000)).toBe(true);
  });
});
