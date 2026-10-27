#!/usr/bin/env bash
set -euo pipefail

: "${COREGRID_KEY:?Export COREGRID_KEY from ~/.coregrid-dev-token before running}"
BASE="${COREGRID_BASE_URL:-http://127.0.0.1:8080}"

echo '[1/4] Dashboard HTML (GET /)'
curl --fail --silent --show-error "$BASE/" | grep -q 'CoreGrid · Job Console'
echo '[2/4] Bundled JS and CSS'
curl --fail --silent --show-error "$BASE/app.js" | grep -q 'function submitJob'
curl --fail --silent --show-error "$BASE/styles.css" | grep -q 'job-list'
echo '[3/4] Authenticated API status'
curl --fail --silent --show-error -H "Authorization: Bearer $COREGRID_KEY" "$BASE/api/health"
printf '\n'
echo '[4/4] Verify browser UI at:'
echo "   $BASE/"
echo 'PASS: Phase 5 bundled dashboard + Phase 4 API available'
