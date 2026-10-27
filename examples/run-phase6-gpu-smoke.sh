#!/usr/bin/env bash
set -euo pipefail
: "${COREGRID_KEY:?Set COREGRID_KEY from ~/.coregrid-dev-token}"
base="http://127.0.0.1:8080"
auth=( -H "Authorization: Bearer $COREGRID_KEY" )
echo "[1/3] Checking GPU is advertised"
health=$(curl -fsS "${auth[@]}" "$base/api/health")
echo "$health"
echo "$health" | grep -q '"gpuReady":true' || { echo "GPU unavailable; verify Coordinator and Worker environment configuration."; exit 1; }
curl -fsS "${auth[@]}" "$base/api/environments" | grep -q '"id":"pytorch-gpu"' || { echo 'pytorch-gpu missing'; exit 1; }
echo "[2/3] Submit examples/gpu-matrix.py via the browser (GPU example button), then check logs."
echo "[3/3] Successful logs should contain: Result device: cuda:0 / GPU computation successful!"
