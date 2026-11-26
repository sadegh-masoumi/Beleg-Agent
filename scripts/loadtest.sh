#!/usr/bin/env bash
# Load test with a simulated model (no Gemini calls). Results: docs/loadtest/results.csv + timeline-*.csv
#
#   scripts/loadtest.sh            all scenarios
#   scripts/loadtest.sh w1-c16     one scenario by label
#
# Each scenario: workers scaled to 0, N documents uploaded through the API, then the workers start and drain the
# backlog. Runs in its own compose project (beleg-loadtest), so the dev stack and its data are not touched.
set -euo pipefail
cd "$(dirname "$0")/.."

LT="docker compose -p beleg-loadtest -f compose.yaml -f compose.loadtest.yaml"
OUT=docs/loadtest
ONLY="${1:-}"

# label workers concurrency latency rpm failure_rate docs
SCENARIOS=(
  "w1-c1     1   1 2s   0 0     60"
  "w1-c4     1   4 2s   0 0    200"
  "w1-c16    1  16 2s   0 0   1000"
  "w1-c64    1  64 2s   0 0   1000"
  "w1-c256   1 256 2s   0 0   1000"
  "w3-c64    3  64 2s   0 0   1000"
  "w3-c64-rpm600 3 64 2s 600 0 1000"
  "w3-c64-fail10 3 64 2s  0 0.1 1000"
)

echo "== building app image and load-test jar"
docker compose --profile dev run --rm dev mvn -B -ntp -q -DskipTests -pl beleg-loadtest -am package
$LT build api
$LT up -d --wait postgres minio api
$LT up -d minio-init

run() {
  local label=$1 workers=$2 conc=$3 latency=$4 rpm=$5 fail=$6 docs=$7
  local tenant="lt-${label}-$(date +%s)"
  echo "== ${label}: ${workers} worker(s) x ${conc} slots, model ${latency}, rpm ${rpm}, failures ${fail}, ${docs} docs"
  export LT_CONCURRENCY=$conc LT_LATENCY=$latency LT_RPM=$rpm LT_FAILURE_RATE=$fail
  $LT up -d --scale worker=0 worker >/dev/null
  $LT --profile dev run --rm --no-deps dev java -jar beleg-loadtest/target/beleg-loadtest.jar upload \
      --api http://api:8080 --tenant "$tenant" --docs "$docs" --out "$OUT"
  $LT up -d --wait --scale worker="$workers" --force-recreate worker
  $LT --profile dev run --rm --no-deps dev java -jar beleg-loadtest/target/beleg-loadtest.jar measure \
      --tenant "$tenant" --label "$label" --workers "$workers" --concurrency "$conc" --latency "$latency" \
      --rpm "$rpm" --failure-rate "$fail" --out "$OUT"
}

for s in "${SCENARIOS[@]}"; do
  read -r label workers conc latency rpm fail docs <<<"$s"
  if [[ -z "$ONLY" || "$ONLY" == "$label" ]]; then
    run "$label" "$workers" "$conc" "$latency" "$rpm" "$fail" "$docs"
  fi
done

echo "== done. Results in $OUT/results.csv. Stop with: $LT down -v"
