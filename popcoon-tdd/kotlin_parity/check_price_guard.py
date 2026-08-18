#!/usr/bin/env python3
"""check_price_guard.py — `realPrice` を統計に使うファイルが ¥0 を除外しているか検査する。

背景 (2026-08):
  「取得失敗を 0 円として記録したレコード」がドメイン全体を汚染していた。書き込み側は
  塞いだ (FallbackScraper cdf61dc / backend 5c0ade0 / AmazonPaApiClient) が、
  既存 DB の行は残るため読み出し側にもガードが要る。総ざらいした結果、**8 経路**で
  判定が壊れていた:

    BuyTimingScorer (ATL 近接 / ボラティリティ) — 95/BUY_NOW が 40/NEUTRAL に反転
    PricePredictionEngine — historic_low が ¥0、本物の高値が外れ値として脱落、
                            買い時確率 0.167 → 0.5
    WeeklyDigestWorker    — ¥0 が必ず「値下がり」に計上される
    PriceChartCanvas      — 下端が ¥0 に張り付き、傾向の読み上げが反転
    TargetPriceChip       — 常に「目標達成」点灯
    SmartCartService      — 実質 0 円のモールが必ず選ばれ「節約額」が嘘になる
    DarkPatternDetector   — 価格を動かしていない販売者に「セール前値上げ」の冤罪
    WatchlistSort / SortAndFilter — ¥0 が「目標に最も近い」「割引率 100%」で先頭に並ぶ

  同じ欠陥を 8 回別々に見つけて直した。9 回目を人間の注意力に頼らないための歯止め。

規則:
  `realPrice` / `real_price` を **統計・比較・集計の文脈** で読むファイルは、
  同じファイル内に正値ガード (`realPrice > 0` 等) を持っていなければならない。
  ファイル単位の粗い判定だが、その粗さゆえに誤報しにくい。
  正当な例外は ALLOWLIST に理由付きで登録する (黙って無視しない)。
"""
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC_DIRS = [ROOT / "app/src/main/java"]

# 統計・比較・集計の文脈で realPrice を読んでいる形
USE = re.compile(
    r"realPrice\s*(?:[-+*/]|<|>|<=|>=|\.toDouble|\.toFloat)"
    r"|(?:min|max|sum|average|count|sorted|filter|fold|reduce)\w*\s*(?:\{|\()[^\n]{0,80}realPrice"
    r"|realPrice\s*\}\s*(?:\)|\.)"
)
# 正値ガード。ラムダ内で it/p に束縛してから比較する書き方も認める。
GUARD = re.compile(
    r"realPrice\s*>\s*0|realPrice\s*<=\s*0|realPrice\s*>=\s*1"
    r"|realPrice\s*\}\s*\.filter\s*\{\s*it\s*>\s*0"
    r"|filter\s*\{\s*(?:it|\w+)\s*>\s*0(?:\.0)?\s*\}"
)

# 例外は理由とセットでのみ許す。
ALLOWLIST: dict[str, str] = {
    # 例: "path/To/File.kt": "理由",
}


def strip_noncode(s: str) -> str:
    s = re.sub(r"/\*.*?\*/", " ", s, flags=re.S)
    s = re.sub(r"//[^\n]*", " ", s)
    return s


def main() -> int:
    offenders, ok_files = [], 0
    for d in SRC_DIRS:
        for f in sorted(d.rglob("*.kt")):
            rel = str(f.relative_to(ROOT))
            text = strip_noncode(io.open(f, encoding="utf-8").read())
            if "realPrice" not in text:
                continue
            uses = list(USE.finditer(text))
            if not uses:
                continue
            if rel in ALLOWLIST:
                print(f"  [allow] {rel} — {ALLOWLIST[rel]}")
                continue
            if GUARD.search(text):
                ok_files += 1
                continue
            line = text[:uses[0].start()].count("\n") + 1
            offenders.append(
                f"{rel}:{line}: realPrice を統計に使っているが ¥0 の除外が無い "
                f"({len(uses)} 箇所)")

    print(f"price-guard check: {ok_files} files read realPrice for statistics, all guarded")
    if offenders:
        print("PRICE GUARD CHECK: FAILED", file=sys.stderr)
        for o in offenders:
            print("  " + o, file=sys.stderr)
        print("  realPrice <= 0 は取得失敗を 0 円として記録した汚染レコードで、", file=sys.stderr)
        print("  実際に成立した価格ではない。統計に入れる前に除外すること。", file=sys.stderr)
        print("  正当な例外なら check_price_guard.py の ALLOWLIST に理由付きで登録する。", file=sys.stderr)
        return 1
    print("PRICE GUARD CHECK: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
