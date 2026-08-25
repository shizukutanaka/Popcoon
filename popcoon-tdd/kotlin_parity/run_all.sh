#!/usr/bin/env bash
# run_all.sh — run every standalone Kotlin verification harness (no Android SDK).
#
# Aggregates the cross-language parity check and the per-source mapper checks.
# Exits non-zero if any harness fails. Intended for both local use and CI
# (see ci/android.yml: the parity job calls this after Gradle is available).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Ensure a bundled Kotlin compiler is reachable. Search the Gradle wrapper cache
# first, then a system Gradle install (GRADLE_HOME, /opt/gradle-*, /usr/share/gradle*)
# — the latter lets this run on CI images / dev boxes where a full Gradle is present
# but the wrapper can't reach services.gradle.org to self-populate.
if ! find "$HOME/.gradle" ${GRADLE_HOME:+"$GRADLE_HOME/lib"} /opt/gradle-*/lib /usr/share/gradle*/lib \
       -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | grep -q .; then
  echo "Populating Gradle wrapper distribution (for bundled kotlinc)…"
  (cd "$HERE/../.." && ./gradlew --version >/dev/null 2>&1) || true
fi

harnesses=(
  "run_compile_core.sh"  # app モジュールの Android 非依存部を実コンパイル (型/override/when 網羅)
  "run.sh"          # 6-function cross-language parity (customs/eco/dark-pattern/predict/buy-timing)
  "run_rakuten.sh"  # RakutenMapper: availability -> stockCount
  "run_yahoo.sh"    # YahooMapper: inStock -> stockCount
  "run_jsonld.sh"   # FallbackScraper stockFromAvailability (schema.org)
  "run_url.sh"      # UrlClassifier: share-intent URL -> Platform+SKU
  "run_points.sh"   # PointSimulator: 実質価格 (point stacking across malls)
  "run_bundle.sh"   # BundlePackDetector: set-sale count extraction (full-width digits)
  "run_matcher.sh"  # ProductMatcher: model-number extraction + title tokenization (full-width)
  "run_jan.sh"      # JanCodeQuery: JAN/EAN-13/8 check-digit validation + UPC->JAN
  "run_trie.sh"     # Trie: autocomplete suggest() order vs popcoon_core.Trie
  "run_deeplinks.sh" # DeepLinks: producer/consumer round-trip (notif/widget vs MainActivity)
  "run_currency.sh" # CurrencyFormatter: locale-independent yen formatting (de_DE/ar guard)
  "run_alerts.sh"   # PriceAlertEvaluator: target-price edge-trigger (Tier 53/54 regression guard)
  "run_calendar.sh" # SaleCalendar: activeSales の重要度順 / nextMajorSale の最小性 (365 日走査)
)

# ハーネスは互いに独立している (それぞれ自前の mktemp -d へ出力し、ソースは読むだけ) ため
# 並列実行できる。各ハーネスは Kotlin コンパイラ JVM を 1 つ起動するので、同時実行数は
# コア数までに絞る (逐次 82 秒 / 4 コア。詰め込みすぎるとメモリと文脈切替で逆に遅くなる)。
# デバッグ時は PARITY_JOBS=1 で逐次実行に戻せる。
JOBS="${PARITY_JOBS:-$(nproc 2>/dev/null || echo 2)}"
OUTDIR="$(mktemp -d)"
trap 'rm -rf "$OUTDIR"' EXIT

for h in "${harnesses[@]}"; do
  {
    if bash "$HERE/$h" > "$OUTDIR/$h.out" 2>&1; then
      echo 0 > "$OUTDIR/$h.rc"
    else
      echo 1 > "$OUTDIR/$h.rc"
    fi
  } &
  # 同時実行数を JOBS までに制限
  while [[ "$(jobs -rp | wc -l)" -ge "$JOBS" ]]; do wait -n; done
done
wait

# **宣言順**に出力する (完了順ではない)。並列化してもログの並びと差分が安定するようにし、
# 既存の grep パターン (✓ / ✗ / matched) もそのまま使えるようにする。
fail=0
for h in "${harnesses[@]}"; do
  echo "═══ $h ═══"
  cat "$OUTDIR/$h.out"
  if [[ "$(cat "$OUTDIR/$h.rc" 2>/dev/null || echo 1)" == "0" ]]; then
    echo "  ✓ $h"
  else
    echo "  ✗ $h FAILED"
    fail=1
  fi
  echo
done

if [[ $fail -ne 0 ]]; then
  echo "PARITY/MAPPER HARNESSES: FAILED"
  exit 1
fi
echo "PARITY/MAPPER HARNESSES: all passed"
