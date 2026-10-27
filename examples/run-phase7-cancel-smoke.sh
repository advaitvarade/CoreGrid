#!/usr/bin/env bash
set -euo pipefail
: "${COREGRID_KEY:?export COREGRID_KEY from ~/.coregrid-dev-token}"
BASE=http://127.0.0.1:8080
AUTH=(-H "Authorization: Bearer $COREGRID_KEY")

echo '[1/5] Verify Coordinator and Worker'
HEALTH=$(curl -fsS "${AUTH[@]}" "$BASE/api/health")
echo "$HEALTH"
echo "$HEALTH" | grep -q '"workerConnected":true' || { echo 'Start WorkerAgent first' >&2; exit 1; }

echo '[2/5] Submit a slow Python job'
RESPONSE=$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"name":"Phase7 cancellation test","environment":"python-basic","cpu":2,"memoryMb":512,"gpu":false,"timeoutSeconds":120,"args":[],"script":"import time\nfor i in range(60):\n print(\"tick\", i, flush=True)\n time.sleep(1)"}' \
  "$BASE/api/jobs")
ID=$(printf '%s' "$RESPONSE" | sed -n 's/.*"id":"\([a-zA-Z0-9-]*\)".*/\1/p')
[[ -n "$ID" ]] || { echo "$RESPONSE"; exit 1; }
echo "Job ID: $ID"

echo '[3/5] Wait for RUNNING state'
STATE=''
for i in $(seq 1 30); do
  STATE=$(curl -fsS "${AUTH[@]}" "$BASE/api/jobs/$ID")
  if echo "$STATE" | grep -q '"status":"RUNNING"'; then break; fi
  sleep 1
done
echo "$STATE" | grep -q '"status":"RUNNING"' || { echo "Job never started: $STATE"; exit 1; }

echo '[4/5] Send HTTP cancellation'
curl -fsS "${AUTH[@]}" -X POST "$BASE/api/jobs/$ID/cancel"
echo

echo '[5/5] Wait for confirmed cancellation and check Docker cleanup'
for i in $(seq 1 30); do
  STATE=$(curl -fsS "${AUTH[@]}" "$BASE/api/jobs/$ID")
  if echo "$STATE" | grep -q '"status":"CANCELED"'; then break; fi
  if echo "$STATE" | grep -q '"status":"INTERRUPTED"'; then echo "$STATE"; exit 1; fi
  sleep 1
done
if ! echo "$STATE" | grep -q '"status":"CANCELED"'; then echo "Unexpected state: $STATE"; exit 1; fi
# Docker container names include this job's ID; verify none remains running.
if docker ps --format '{{.Names}}' | grep -Fq "coregrid-$ID-"; then
  echo 'ERROR: a canceled job still has a running Docker container' >&2
  exit 1
fi
echo "PASS: running job $ID canceled, no matching running container"
