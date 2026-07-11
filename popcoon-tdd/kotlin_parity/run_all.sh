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
)

fail=0
for h in "${harnesses[@]}"; do
  echo "═══ $h ═══"
  if bash "$HERE/$h"; then
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
