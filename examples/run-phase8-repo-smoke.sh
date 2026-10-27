#!/usr/bin/env bash
set -euo pipefail
: "${COREGRID_KEY:?Export COREGRID_KEY first}"
: "${COREGRID_TEST_REPO_URL:?Export a public GitHub repository URL containing main.py and helpers.py}"
base="http://127.0.0.1:8080"
printf '{"name":"Phase8 GitHub test","repositoryUrl":"%s","entryPoint":"main.py","environment":"python-basic","cpu":2,"gpu":false,"memoryMb":512,"timeoutSeconds":120,"args":[]}\n' "$COREGRID_TEST_REPO_URL" > /tmp/coregrid-p8-submit.json
result=$(curl -fsS --max-time 90 -H "Authorization: Bearer $COREGRID_KEY" -H 'Content-Type: application/json' -d @/tmp/coregrid-p8-submit.json "$base/api/jobs/repository")
echo "$result"
id=$(printf '%s' "$result" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
for i in $(seq 1 45); do
  sleep 2
  state=$(curl -fsS -H "Authorization: Bearer $COREGRID_KEY" "$base/api/jobs/$id")
  status=$(printf '%s' "$state" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])')
  printf '%s\n' "$status"
  if [ "$status" = SUCCEEDED ]; then break; fi
  if [[ "$status" = FAILED || "$status" = REJECTED || "$status" = INTERRUPTED || "$status" = TIMED_OUT ]]; then
    echo "$state"; exit 1
  fi
done
[ "${status:-}" = SUCCEEDED ] || { echo "Job did not finish"; exit 1; }
curl -fsS -H "Authorization: Bearer $COREGRID_KEY" "$base/api/jobs/$id/source"
echo
curl -fsS -H "Authorization: Bearer $COREGRID_KEY" "$base/api/jobs/$id/artifacts" -o "/tmp/coregrid-p8-$id.zip"
unzip -l "/tmp/coregrid-p8-$id.zip" | grep 'output/squares.csv'
echo "PASS: Phase 8 GitHub repository -> TCP -> Docker -> artifacts"
