#!/usr/bin/env bash
# run.sh — execute cross-language parity for the scalar functions (customs / eco)
# WITHOUT an Android SDK, using the Kotlin compiler bundled in the Gradle distribution.
#
# Compiles the REAL Kotlin sources (CustomsSimulator, EcoEthicsScorer) + the harness,
# runs them on the JVM, and pipes the output into compare_oracle.py which re-checks
# every case against the verified Python oracle (popcoon_core).
#
# Exit 0 iff all cases match. Intended for local verification and (optionally) CI.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/com/example/popcoon"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Locate the Kotlin compiler bundled with the Gradle wrapper distribution.
KC="$(find "$HOME/.gradle" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | head -1 || true)"
if [[ -z "$KC" ]]; then
  echo "ERROR: kotlin-compiler-embeddable jar not found under ~/.gradle." >&2
  echo "       Run './gradlew --version' once to populate the wrapper distribution." >&2
  exit 2
fi
LIB="$(dirname "$KC")"
ST="$(find "$LIB" -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1)"
# kotlinx-serialization runtime: Product.kt is @Serializable. The compiler PLUGIN is
# absent, but the annotations are inert without it and the logic never calls
# .serializer(), so the real sources compile against the runtime jars alone.
SER="$(find "$LIB" -name 'kotlinx-serialization-core-jvm-*.jar' | head -1):$(find "$LIB" -name 'kotlinx-serialization-json-jvm-*.jar' | head -1)"

echo "compiler: $KC"
java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$ST:$SER" -d "$OUT/parity.jar" -nowarn -no-reflect \
  "$SRC/data/model/Product.kt" \
  "$SRC/feature/cart/CrossMallCartOptimizer.kt" \
  "$SRC/feature/crossborder/CustomsSimulator.kt" \
  "$SRC/feature/ethics/EcoEthicsScorer.kt" \
  "$SRC/feature/darkpattern/DarkPatternDetector.kt" \
  "$SRC/feature/prediction/ConformalInterval.kt" \
  "$SRC/feature/prediction/SeasonalDecompForecast.kt" \
  "$SRC/feature/prediction/PricePredictionEngine.kt" \
  "$SRC/feature/calendar/SaleCalendar.kt" \
  "$SRC/feature/scorer/SeasonalDowSignal.kt" \
  "$SRC/feature/scorer/BuyTimingScorer.kt" \
  "$HERE/ParityHarness.kt" 2>&1 | grep -v 'unable to find kotlin' || true

# Force UTF-8 stdout: the JVM may default to ASCII (stdout.encoding=ANSI_X3.4-1968),
# which would mangle the Japanese category/cert literals to '?'.
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
  -cp "$OUT/parity.jar:$ST:$SER" ParityHarnessKt > "$OUT/kotlin_out.tsv"

echo "kotlin emitted $(wc -l < "$OUT/kotlin_out.tsv") cases"
PYTHONIOENCODING=UTF-8 python3 "$HERE/compare_oracle.py" < "$OUT/kotlin_out.tsv"
