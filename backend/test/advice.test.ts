/**
 * POST /v1/advice (Claude API プロキシ) のテスト。
 *
 * vitest.config.ts で @cloudflare/vitest-pool-workers は有効化済み (2026-07〜、
 * worker.test.ts が実ハンドラーを import して実行する)。このファイルは意図的に
 * ルートの契約となる純粋ロジック (payload 検証・レート制限バケット分離) を境界値・
 * プロパティレベルで再実装し仕様として固定する — worker.test.ts (実ハンドラー越しの
 * HTTP 層・KV 実処理・認可検証) を補完する役割。ANTHROPIC_API_KEY を要する実際の
 * Claude API 呼び出しは外部 API 依存のためどちらのレイヤーでも検証対象外。
 */

import { describe, it, expect } from "vitest";

// src/index.ts::handleRequest の POST /v1/advice ブロックにあるバリデーション契約。
function validateAdvicePayload(body: unknown): { ok: true } | { ok: false; reason: string } {
  const b = body as { system?: string; userPrompt?: string } | null;
  if (!b?.system || !b?.userPrompt) return { ok: false, reason: "invalid payload" };
  if (b.system.length > 2000) return { ok: false, reason: "system too long" };
  if (b.userPrompt.length > 2000) return { ok: false, reason: "userPrompt too long" };
  return { ok: true };
}

describe("POST /v1/advice payload validation", () => {
  it("system と userPrompt が両方あれば有効", () => {
    expect(validateAdvicePayload({ system: "s", userPrompt: "u" })).toEqual({ ok: true });
  });

  it("system 欠落は無効", () => {
    expect(validateAdvicePayload({ userPrompt: "u" })).toEqual({ ok: false, reason: "invalid payload" });
  });

  it("userPrompt 欠落は無効", () => {
    expect(validateAdvicePayload({ system: "s" })).toEqual({ ok: false, reason: "invalid payload" });
  });

  it("body が null/未定義でも例外を投げず無効判定する", () => {
    expect(validateAdvicePayload(null)).toEqual({ ok: false, reason: "invalid payload" });
    expect(validateAdvicePayload(undefined)).toEqual({ ok: false, reason: "invalid payload" });
  });

  it("system が2000文字を超えると拒否 (プロンプトインジェクション/コスト対策)", () => {
    const result = validateAdvicePayload({ system: "x".repeat(2001), userPrompt: "u" });
    expect(result).toEqual({ ok: false, reason: "system too long" });
  });

  it("userPrompt が2000文字を超えると拒否", () => {
    const result = validateAdvicePayload({ system: "s", userPrompt: "x".repeat(2001) });
    expect(result).toEqual({ ok: false, reason: "userPrompt too long" });
  });

  it("ちょうど2000文字は許容する (境界値)", () => {
    const result = validateAdvicePayload({ system: "x".repeat(2000), userPrompt: "y".repeat(2000) });
    expect(result).toEqual({ ok: true });
  });
});

// ── レート制限バケット分離 ────────────────────────────────────────────────────
// src/index.ts::rateLimit は key = `rate:${ip}:${minute}` で構築される。
// /v1/advice は `advice:${ip}` を第二引数に渡すことで独立したキー空間
// (`rate:advice:${ip}:${minute}`) を得る。history/alerts の書き込み制限
// (`rate:${ip}:${minute}`) と衝突しないことを固定する — LLM 呼び出しは
// 課金コストが高いためより厳しい上限 (3/分) を他の書き込みから独立させたい。
function rateLimitKey(ip: string, minuteBucket: number): string {
  return `rate:${ip}:${minuteBucket}`;
}

describe("advice レート制限バケットの分離", () => {
  it("advice バケットは通常の書き込みバケットと異なるキーになる", () => {
    const minute = 12345;
    const normalKey = rateLimitKey("1.2.3.4", minute);
    const adviceKey = rateLimitKey(`advice:1.2.3.4`, minute);
    expect(adviceKey).not.toBe(normalKey);
    expect(adviceKey).toBe("rate:advice:1.2.3.4:12345");
  });

  it("異なる IP の advice バケットは互いに独立する", () => {
    const minute = 1;
    expect(rateLimitKey("advice:1.1.1.1", minute)).not.toBe(rateLimitKey("advice:2.2.2.2", minute));
  });
});

// ── Claude 応答からのテキスト抽出 ────────────────────────────────────────────
// src/index.ts の POST /v1/advice ハンドラーが Claude API レスポンスから
// text content を取り出すロジックの契約。
function extractAdviceText(data: { content?: Array<{ type: string; text?: string }> } | null): string | null {
  return data?.content?.find(c => c.type === "text")?.text ?? null;
}

describe("Claude レスポンスからのテキスト抽出", () => {
  it("type: text の content からテキストを取り出す", () => {
    expect(extractAdviceText({ content: [{ type: "text", text: "今が買い時です" }] })).toBe("今が買い時です");
  });

  it("text 以外の content タイプは無視する", () => {
    expect(extractAdviceText({ content: [{ type: "tool_use" }, { type: "text", text: "OK" }] })).toBe("OK");
  });

  it("content が空/欠落なら null (呼び出し側で 502 扱い)", () => {
    expect(extractAdviceText({ content: [] })).toBeNull();
    expect(extractAdviceText(null)).toBeNull();
  });
});
