# CoreGrid Phase 7 — Cancellation and Container Lifecycle Safety

This upgrade **extends Phase 6**. Keep your working Phase 6 backup and preserve your `data/` and `jobs/` directories when copying files.

## What's implemented

- `POST /api/jobs/{id}/cancel`: authenticated, owner-scoped cancellation.
- `CANCELED` for jobs stopped before dispatch or after a confirmed worker response; `CANCELING` while the worker stops a running container.
- `CANCEL_JOB` and `CANCELED` TCP messages between Coordinator and Worker Agent.
- Thread-safe cancellation for queued and executing jobs, with cancellation checks before Docker execution and resource permits released on all paths.
- Docker lifecycle changed to **create → start/attach → remove** so a job has a named container before it starts. `docker rm -f` terminates running jobs.
- Container labels (`org.coregrid.managed=true`, `org.coregrid.instance`) and orphan cleanup when the Worker Agent starts.
- Local worker lock file to prevent two agents using the same job directory.
- Existing controls remain: local-only HTTP/TCP, API token, job ownership, read-only script input, writable job-specific output, disabled container network, non-root UID, dropped Linux capabilities, no-new-privileges, read-only container root, CPU/RAM/PID/time limits.
- Maximum of 8 active or queued jobs per account.
- New **Cancel job** action in the browser.

## Setup in WSL (Windows + Ubuntu)

1. Back up the previous folder:

   ```bash
   cd ~/projects/CoreGrid
   cp -a coregrid-worker coregrid-worker-backup-phase6
   explorer.exe .
   ```

2. Extract the Phase 7 ZIP into this `CoreGrid` folder and select **Replace files in destination**. This updates code without removing `data/` or `jobs/`.
3. Stop both the old Coordinator and Worker (`Ctrl+C`) before starting the new JAR. Existing jobs should be allowed to finish before stopping, if possible.
4. Build and run tests:

   ```bash
   cd ~/projects/CoreGrid/coregrid-worker
   mvn clean package
   java -cp target/classes com.coregrid.worker.CancellationSelfTest
   java -cp target/classes com.coregrid.network.NetworkCancellationSelfTest
   java -cp target/classes com.coregrid.worker.SchedulerSelfTest
   java -cp target/classes com.coregrid.network.NetworkIntegrationSelfTest
   ```

5. Terminal 1 — Coordinator:

   ```bash
   cd ~/projects/CoreGrid/coregrid-worker
   export COREGRID_KEY=$(cat ~/.coregrid-dev-token)
   export COREGRID_USERS="advait:$COREGRID_KEY"
   export COREGRID_GPU_IMAGE="pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime"
   java -jar target/coregrid-worker-0.7.0-SNAPSHOT.jar
   ```

6. Terminal 2 — Worker:

   ```bash
   cd ~/projects/CoreGrid/coregrid-worker
   export COREGRID_GPU_IMAGE="pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime"
   java -cp target/coregrid-worker-0.7.0-SNAPSHOT.jar com.coregrid.network.WorkerAgent
   ```

7. Open `http://127.0.0.1:8080/` in your Ubuntu browser (WSLg). If Windows Chrome still cannot connect to WSL, use the working Linux browser. Submit a simple 60-second script and click **Cancel job** while it runs.
8. Third Ubuntu terminal — end-to-end test:

   ```bash
   cd ~/projects/CoreGrid/coregrid-worker
   export COREGRID_KEY=$(cat ~/.coregrid-dev-token)
   bash examples/run-phase7-cancel-smoke.sh
   ```

   The test expects `CANCELED` and verifies there is no **running** Docker container for that job. `docker ps -a --filter label=org.coregrid.managed=true` shows remaining CoreGrid containers if you need to inspect them.

## Expected HTTP behavior

- `POST /api/jobs/{id}/cancel` with `Authorization: Bearer <token>`
- For a queued job: `202` with `status: "CANCELED"`.
- For a dispatched/running job: `202` with `status: "CANCELING"`, followed by `CANCELED` when the Worker confirms.
- If the job finishes before cancellation is delivered, its actual final state may be `SUCCEEDED` or `FAILED`.
- If a worker disconnects mid-cancellation and the final outcome is uncertain, CoreGrid records `INTERRUPTED` instead of falsely reporting success.
- Requests for other users' jobs return `404`.

## Linux lab deployment notes

1. Confirm lab administrator approves a job-execution server. Install JDK 21, Docker Engine, supported NVIDIA drivers and NVIDIA Container Toolkit (if GPU jobs are needed).
2. Keep **one** Worker Agent per local `COREGRID_DATA_DIR`; do not run more than one with the same `COREGRID_INSTANCE_ID`. Use a unique `COREGRID_INSTANCE_ID` for independent installations.
3. Configure persistent `COREGRID_DATA_DIR` and an appropriate service account. Keep the Docker socket and data directory inaccessible to students.
4. Keep the Coordinator HTTP/TCP bound to loopback. Use authenticated private HTTPS access (VPN and reverse proxy) after hardening; do **not** publicly expose port 8080 or the TCP worker protocol.
5. Use prebuilt pinned Docker image versions. Do not give user jobs a Docker socket mount or unrestricted network access.

## Security and reliability limitations — important

**This version is not a hardened arbitrary-code execution service.** Docker containers share the host kernel; GPU access shares the host GPU driver. Docker's CPU quota is not a dedicated-core reservation and GPU access is not a VRAM quota. A default Docker sandbox is not guaranteed to contain hostile code.

Currently cancellation is best-effort against Docker daemon failures; if Docker itself is unavailable, manual administrator inspection may be needed. Abrupt machine power loss may leave files or containers. On startup the Worker removes leftover containers with this instance's labels, but a full durable worker lease/reconciliation mechanism is not implemented. The SQLite database marks in-flight Coordinator jobs interrupted after restart, rather than automatically retrying them (avoids duplicate execution). Do not allow unknown users or public GitHub repos to execute yet.

**Phase 7B before lab multiuser deployment:** OS-level separation or microVM policy for mutually untrusted users, more complete filesystem/output quotas, audit logging, rate limiting, durable leases, encrypted remote access and institutional security review.

**Phase 8 (planned):** trusted/approved GitHub repository cloning, multi-file execution and dependency-aware pinned environment builds, after the isolation review.
