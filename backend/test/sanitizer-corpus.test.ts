/**
 * sanitizePii を **Kotlin 側と同じ共有コーパス**で検証する。
 *
 * クライアント (`LogSanitizer.kt`) とサーバー (`sanitizePii`) は同一規則である必要がある。
 * サーバーは「サニタイズしても変わらない = PII を含まない」で二重チェックするので、
 * 片方だけ規則が古いと (a) クライアントが伏せ切れなかった PII をサーバーが検出して
 * 正当なレポートを 400 にする、(b) 逆に伏せ漏れがそのまま保存される、のどちらかが起きる。
 * 実際に `PopcoonLogger.sanitize` が 3 パターン古いまま放置されていた。
 *
 * 期待値は `popcoon-tdd/kotlin_parity/sanitizer/corpus.tsv` にあり、正規表現から
 * 手導出したもの — **どちらの実装の出力でもない**。同じファイルを
 * `popcoon-tdd/kotlin_parity/run_sanitizer.sh` が実 Kotlin に対して回すため、
 * 2 言語の一致が fixture drift 無しに検証される。
 *
 * `?raw` で取り込むのは Workers ランタイムに node:fs が無いため (Vite がビルド時に inline する)。
 */

import { describe, it, expect } from "vitest";
import { sanitizePii } from "../src/index";
import corpusRaw from "../../popcoon-tdd/kotlin_parity/sanitizer/corpus.tsv?raw";

interface Case {
  input: string;
  expected: string;
  why: string;
}

function parseCorpus(raw: string): Case[] {
  return raw
    .split("\n")
    .filter((line) => line.trim() !== "" && !line.startsWith("#"))
    .map((line) => {
      const [input, expected, why] = line.split("\t");
      return { input, expected, why: why ?? "" };
    });
}

const cases = parseCorpus(corpusRaw);

describe("sanitizePii — Kotlin と共有のコーパス", () => {
  it("コーパスが空でない (取り込み経路が壊れていないことの確認)", () => {
    expect(cases.length).toBeGreaterThanOrEqual(15);
  });

  it("全ケースが手導出の期待値と一致する", () => {
    const mismatches = cases
      .filter((c) => sanitizePii(c.input) !== c.expected)
      .map((c) => `input=<${c.input}> got=<${sanitizePii(c.input)}> want=<${c.expected}> (${c.why})`);
    expect(mismatches).toEqual([]);
  });

  it("全ケースが冪等 (二重チェックが正当なレポートを拒否しない)", () => {
    const notIdempotent = cases
      .map((c) => sanitizePii(c.input))
      .filter((once) => sanitizePii(once) !== once);
    expect(notIdempotent).toEqual([]);
  });
});
