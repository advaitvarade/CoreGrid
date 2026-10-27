# CoreGrid Phase 6 — GPU environment support

This update starts with the **working Phase 5** code, adding an approved PyTorch image, optional ML image, CUDA worker detection, a one-GPU-at-a-time scheduler, and a browser GPU test.

## Before you begin

- Java 21, Maven, Docker Desktop/WSL2 already working.
- Docker GPU access already verified with NVIDIA.
- PyTorch image already downloaded and tested:
  `pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime`.
- Stop the running Coordinator and Worker (`Ctrl+C`) before replacing code. Back up Phase 5 first, and **preserve your `data/` directory and `.coregrid-dev-token`**.

## Install

Extract and merge `coregrid-worker/` into `~/projects/CoreGrid/coregrid-worker/`.

```bash
cd ~/projects/CoreGrid/coregrid-worker
mvn clean package
```

The resulting JAR is `target/coregrid-worker-0.6.0-SNAPSHOT.jar`.

Ensure both base and GPU images are locally available:

```bash
docker image inspect python:3.11-slim
docker image inspect pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime
```

### Terminal 1: coordinator

```bash
cd ~/projects/CoreGrid/coregrid-worker
export COREGRID_KEY=$(cat ~/.coregrid-dev-token)
export COREGRID_USERS="advait:$COREGRID_KEY"
export COREGRID_GPU_IMAGE="pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime"
java -jar target/coregrid-worker-0.6.0-SNAPSHOT.jar
```

### Terminal 2: worker

```bash
cd ~/projects/CoreGrid/coregrid-worker
export COREGRID_GPU_IMAGE="pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime"
java -cp target/coregrid-worker-0.6.0-SNAPSHOT.jar com.coregrid.network.WorkerAgent
```

The worker checks `nvidia-smi`, confirms its GPU image is cached, and executes a PyTorch CUDA probe in a temporary Docker container before advertising GPU readiness. This may take several seconds. If it fails, the worker still processes CPU jobs but does not advertise GPU capability.

### Terminal 3: check health

```bash
cd ~/projects/CoreGrid/coregrid-worker
export COREGRID_KEY=$(cat ~/.coregrid-dev-token)
curl -s -H "Authorization: Bearer $COREGRID_KEY" http://127.0.0.1:8080/api/health
curl -s -H "Authorization: Bearer $COREGRID_KEY" http://127.0.0.1:8080/api/environments
bash examples/run-phase6-gpu-smoke.sh
```

`gpuReady` should be `true`, and `/api/environments` should include `pytorch-gpu`.

### Test from browser

Open `http://127.0.0.1:8080/` in the **Ubuntu Linux browser** you used in Phase 5 (Windows-to-WSL localhost forwarding was not working on your setup).

1. Connect with the existing API token.
2. Click **GPU example**. It selects `pytorch-gpu` and requests 4 GB system RAM.
3. Click **Submit job**.
4. Confirm `SUCCEEDED` and `stdout` including `Result device: cuda:0`.
5. Download the ZIP. It should contain `output/result.pt` plus logs.

The 2000 x 2000 matrix is ~16 MB in float32; this test is suitable for your 6 GB RTX 4050. PyTorch itself uses additional CPU/GPU memory.

## Optional: add a separate CPU ML environment

```bash
docker build -t coregrid/ml:1.0 docker-images/machine-learning
```

Set `export COREGRID_ML_IMAGE="coregrid/ml:1.0"` in **both** Coordinator and Worker terminals and restart both. This image provides preinstalled NumPy/Pandas/scikit-learn/Matplotlib; the job does not download packages during execution.

## Limits and security

- Scheduler admits at most **one GPU job at a time** across this Worker. This is an application reservation, **not** a hard VRAM quota or physical GPU partition.
- The worker also admits up to 4 scheduling CPU slots and runs 2 concurrent jobs at most. Docker applies CPU-time and system-memory limits to each container.
- GPU job selection is by allowlisted `environment` ID; users cannot submit arbitrary Docker image names.
- Images must be cached. Docker runs with `--pull=never`, `--network=none`, and the existing Phase 5 container restrictions.
- Docker containers are **not a guaranteed sandbox against untrusted or malicious programs**, especially when exposing a GPU driver. Use trusted code for now. Strengthen isolation, access policy, resource controls and host permissions before allowing arbitrary lab users.
- Phase 6 still listens only on localhost and assumes Coordinator and Worker share one filesystem. Remote lab deployment and secure access come later.
- GPU readiness is established at agent registration. If the GPU becomes unavailable later, a submitted job can fail; adding periodic telemetry and reconnect recovery is future work.

## Regression tests

```bash
java -cp target/classes com.coregrid.worker.SchedulerSelfTest
java -cp target/classes com.coregrid.network.ProtocolSelfTest
java -cp target/classes com.coregrid.network.NetworkIntegrationSelfTest
java -cp target/classes com.coregrid.api.WebAssetsSelfTest
```
