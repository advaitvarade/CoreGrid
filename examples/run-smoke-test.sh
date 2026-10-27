#!/usr/bin/env bash
set -euo pipefail
: "${COREGRID_KEY:?Set COREGRID_KEY to the bearer token configured for your user on the server}"
BASE=http://127.0.0.1:8080
HEADER="Authorization: Bearer $COREGRID_KEY"

echo '[1/5] Checking API and worker connection...'
HEALTH=$(curl -fsS -H "$HEADER" "$BASE/api/health")
echo "$HEALTH"
if ! echo "$HEALTH" | grep -q '"workerConnected":true'; then
  echo 'Start WorkerAgent first; workerConnected=false' >&2
  exit 1
fi

echo '[2/5] Submitting sample Python program...'
RESP=$(curl -fsS -X POST -H "$HEADER" -H 'Content-Type: application/json' \
    --data-binary @examples/sample-job.json "$BASE/api/jobs")
echo "$RESP"
ID=$(printf '%s' "$RESP" | sed -n 's/.*"id":"\([a-zA-Z0-9-]*\)".*/\1/p')
if [[ -z "$ID" ]]; then echo 'Could not parse job ID' >&2; exit 1; fi

echo "[3/5] Waiting for job $ID (max 60 seconds)..."
for i in $(seq 1 60); do
  STATE=$(curl -fsS -H "$HEADER" "$BASE/api/jobs/$ID")
  if echo "$STATE" | grep -q '"status":"SUCCEEDED"'; then break; fi
  if echo "$STATE" | grep -Eq '"status":"(FAILED|TIMED_OUT|REJECTED|INTERRUPTED)"'; then
    echo "$STATE"; echo 'Job failed' >&2; exit 1
  fi
  sleep 1
done
if ! echo "$STATE" | grep -q '"status":"SUCCEEDED"'; then
  echo "$STATE"; echo 'Job did not finish in time' >&2; exit 1
fi
echo "$STATE"

echo '[4/5] Reading output logs...'
curl -fsS -H "$HEADER" "$BASE/api/jobs/$ID/logs"
echo

echo '[5/5] Downloading result ZIP and checking contents...'
curl -fsS -H "$HEADER" "$BASE/api/jobs/$ID/artifacts" -o "/tmp/coregrid-result-$ID.zip"
unzip -l "/tmp/coregrid-result-$ID.zip"
echo "PASS: Phase 4 HTTP -> SQLite -> TCP -> Docker -> ZIP (job $ID)"
