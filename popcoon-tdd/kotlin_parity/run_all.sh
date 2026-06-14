#!/usr/bin/env bash
# run_all.sh — run every standalone Kotlin verification harness (no Android SDK).
#
# Aggregates the cross-language parity check and the per-source mapper checks.
# Exits non-zero if any harness fails. Intended for both local use and CI
# (see ci/android.yml: the parity job calls this after Gradle is available).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Ensure the bundled Kotlin compiler exists (populated by the Gradle wrapper).
if ! find "$HOME/.gradle" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | grep -q .; then
  echo "Populating Gradle wrapper distribution (for bundled kotlinc)…"
  (cd "$HERE/../.." && ./gradlew --version >/dev/null 2>&1) || true
fi

harnesses=(
  "run.sh"          # 6-function cross-language parity (customs/eco/dark-pattern/predict/buy-timing)
  "run_rakuten.sh"  # RakutenMapper: availability -> stockCount
  "run_yahoo.sh"    # YahooMapper: inStock -> stockCount
  "run_jsonld.sh"   # FallbackScraper stockFromAvailability (schema.org)
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
