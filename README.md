# CoreGrid Phase 6 (GPU update)

**Start with [README_PHASE6.md](README_PHASE6.md)** for the current GPU image setup, Java worker/coordinator startup and browser test. Prior Phase 5 instructions below remain for historical reference.

---

# CoreGrid Phase 4 — HTTP API + SQLite + TCP Worker + Docker

This ZIP is an **upgrade of your Phase 3 `coregrid-worker` project**. It keeps the previous demos and self-tests, and adds an authenticated HTTP API, durable SQLite job records, and user-submitted Python scripts.

**Development-only warning:** Run exclusively on your own computer or a trusted, controlled lab machine. Docker provides useful isolation but is **not a hardened hostile-code sandbox**, especially with GPU access. HTTP and worker TCP bind to **127.0.0.1 only**. Do not port-forward these listeners or expose them publicly. Do not run unknown students' scripts until additional sandbox hardening and administrative approval.

## Setup on Ubuntu WSL2

Requirements: Java 21, Maven, Docker Desktop WSL integration, local `python:3.11-slim` Docker image (or pull with `docker pull python:3.11-slim`).

From your project directory:

```bash
cd ~/projects/CoreGrid/coregrid-worker
mvn clean package
```

On the first Maven build, Maven must download dependencies, including SQLite JDBC and Jackson. (The existing Phase 3 project did not need them.) The output `target/coregrid-worker-0.4.0-SNAPSHOT.jar` is a **fat JAR** and can run both applications.

Create a local API key:

```bash
export COREGRID_KEY="$(openssl rand -hex 24)"
export COREGRID_USERS="advait:$COREGRID_KEY"
```

Important: Run **all three terminals from the same project directory** and set `COREGRID_DATA_DIR` the same in both Java process terminals (default is `data`). Setting `COREGRID_USERS` is required only by the coordinator; keep the key secret.

### Terminal 1: Coordinator + HTTP server

```bash
cd ~/projects/CoreGrid/coregrid-worker
export COREGRID_USERS='advait:REPLACE_WITH_YOUR_LONG_RANDOM_TOKEN'
java -jar target/coregrid-worker-0.4.0-SNAPSHOT.jar
```

Prints `HTTP: http://127.0.0.1:8080/api/health` and `TCP: 127.0.0.1:9090`. **This remains running.** `data/coregrid.db` is created automatically.

### Terminal 2: Docker Worker Agent

```bash
cd ~/projects/CoreGrid/coregrid-worker
java -cp target/coregrid-worker-0.4.0-SNAPSHOT.jar com.coregrid.network.WorkerAgent
```

The worker connects to `127.0.0.1:9090`. The Coordinator and Worker both access `data/jobs/remote-<jobId>` (same filesystem).

### Terminal 3: Submit and inspect

```bash
cd ~/projects/CoreGrid/coregrid-worker
export COREGRID_KEY='REPLACE_WITH_THE_SAME_TOKEN'
curl -sS -H "Authorization: Bearer $COREGRID_KEY" http://127.0.0.1:8080/api/health
curl -sS -H "Authorization: Bearer $COREGRID_KEY" http://127.0.0.1:8080/api/environments
curl -sS -X POST http://127.0.0.1:8080/api/jobs \
  -H "Authorization: Bearer $COREGRID_KEY" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/sample-job.json
```

The POST returns `202` and `{ "id":"...", "status":"QUEUED", "statusUrl":"/api/jobs/..." }`. Copy the ID:

```bash
JOB_ID='paste-the-id-here'
curl -sS -H "Authorization: Bearer $COREGRID_KEY" "http://127.0.0.1:8080/api/jobs/$JOB_ID"
curl -sS -H "Authorization: Bearer $COREGRID_KEY" "http://127.0.0.1:8080/api/jobs/$JOB_ID/logs"
curl -fSL -H "Authorization: Bearer $COREGRID_KEY" \
  "http://127.0.0.1:8080/api/jobs/$JOB_ID/artifacts" -o "result-$JOB_ID.zip"
unzip -l "result-$JOB_ID.zip"
```

Output should include `output/squares.csv`, `logs/stdout.log` and `logs/stderr.log`.

## API

- `GET /api/health` : coordinator status and worker connection
- `GET /api/environments` : locally allowed execution images
- `POST /api/jobs` : submit a single Python source file **as a JSON `script` string** (not multipart yet)
- `GET /api/jobs` : list jobs belonging to your authenticated API token/user
- `GET /api/jobs/{id}` : job status and metadata
- `GET /api/jobs/{id}/logs` : read bounded stdout/stderr
- `GET /api/jobs/{id}/artifacts` : ZIP result files and logs for terminal jobs

API authentication: `Authorization: Bearer <token>`. You can configure multiple local users with `COREGRID_USERS='advait:longtokenA,student2:longtokenB'`. Each token maps to an owner and results are owner-scoped. No credentials are embedded in the repository; keep the environment variable private. This is not production authentication—Phase 5 will add secure deployment infrastructure and user administration.

Submit accepts `name`, `script`, `environment` (`python-basic`), `cpu` 1..4, `gpu` false, `memoryMb` 256..4096, `timeoutSeconds` 5..1800, `args` (up to 16 strings). Optional fields have defaults. Arguments are passed directly to Python with `ProcessBuilder`/Docker **without a shell**, so do not supply `python main.py` in args.

**GPU environment:** The `pytorch-gpu` selection appears only if the Coordinator has `COREGRID_GPU_IMAGE` set. The Worker must have the same variable pointing at an existing, locally cached compatible CUDA PyTorch image, and NVIDIA Docker GPU support must be configured. This phase does not install or construct that heavyweight image. Never choose an untrusted image. CPU scheduling slots are application admission controls, not dedicated CPU reservations. GPU jobs use an exclusive scheduler slot per worker.

## Architecture

```
Postman / curl
    |
    v
Java HttpServer (127.0.0.1:8080)
    |  authenticate user, validate & save main.py
    v
SQLite JobRepository (data/coregrid.db)
    |
    v
WorkerHub queue -> Java TCP WireConnection (127.0.0.1:9090)
    |
    v
WorkerAgent -> JobScheduler -> ResourceManager -> DockerJobRunner
    |                                               |
    |                                        Python main.py + args
    v                                               |
Coordinator receives STATUS  <--------- container exit + logs + output
    |
    v
GET status, logs, artifacts (only the submitting user)
```

`WorkerHub` and the Coordinator use one TCP-connected worker at a time. Previous Phase 3 `CoordinatorMain` and self-tests remain included for reference, but **don't run `CoordinatorMain` at the same time as `ApiServerMain`** (both use port 9090). Phase 4 wire framing is protocol version 2, so use the Phase 4 Coordinator and Worker together.

## Expected testing

Run these in Ubuntu:

```bash
java -cp target/coregrid-worker-0.4.0-SNAPSHOT.jar com.coregrid.worker.SchedulerSelfTest
java -cp target/coregrid-worker-0.4.0-SNAPSHOT.jar com.coregrid.network.ProtocolSelfTest
java -cp target/coregrid-worker-0.4.0-SNAPSHOT.jar com.coregrid.network.NetworkIntegrationSelfTest
```

Also test: POST malformed JSON (400), missing/wrong token (401), another user's job ID (404), and retrieving logs/output once the job completes.

**Restart behavior:** SQLite survives restarts; nonterminal submissions from before the restart become `INTERRUPTED` instead of silently being retried. Jobs submitted *while the Coordinator is running but no worker is connected* queue until one connects. If dispatch outcome is unknown because of a TCP error, the coordinator marks that job `INTERRUPTED`, avoiding accidental duplicate execution.

**Known Phase 4 limitations:** No web dashboard, no multipart file upload, no job cancel API, no heartbeats/reconnection replay, no automatic input artifact uploads beyond one Python source string, no installation of arbitrary packages, no production identity/TLS, and no hardened sandbox for hostile user code. All listeners bind localhost; this intentionally does **not** yet provide home-to-lab remote access. SQLite schema from Phase 4 is private to this version. Jobs that produce huge output files are capped at 50 MiB for ZIP download; stream/log limits are not yet complete quota enforcement.

---

# Phase 5 — Web Dashboard

Phase 5 builds on the **same Phase 4 HTTP + SQLite + TCP + Docker backend**. It adds a same-origin,
framework-free browser application bundled in the executable JAR. No Node.js, npm, React or separate frontend
server is necessary. Open **http://127.0.0.1:8080/** on the Windows PC while the Java server runs in WSL.

### Upgrade from Phase 4

1. Back up your working Phase 4 folder before replacing files.
2. Extract the Phase 5 `coregrid-worker` folder over Phase 4, replacing overlapping source files.
3. Build with `mvn clean package` from `~/projects/CoreGrid/coregrid-worker`.
4. Keep the Coordinator and Worker Agent in separate terminals as in Phase 4.
5. Open **http://127.0.0.1:8080/** in your browser; connect with the API token from `cat ~/.coregrid-dev-token`.

**Terminal 1 — Coordinator**

```bash
cd ~/projects/CoreGrid/coregrid-worker
export COREGRID_KEY=$(cat ~/.coregrid-dev-token)
export COREGRID_USERS="advait:$COREGRID_KEY"
java -jar target/coregrid-worker-0.5.0-SNAPSHOT.jar
```

**Terminal 2 — Worker Agent**

```bash
cd ~/projects/CoreGrid/coregrid-worker
java -cp target/coregrid-worker-0.5.0-SNAPSHOT.jar com.coregrid.network.WorkerAgent
```

**Terminal 3 — Optional smoke test**

```bash
cd ~/projects/CoreGrid/coregrid-worker
export COREGRID_KEY=$(cat ~/.coregrid-dev-token)
bash examples/run-phase5-web-smoke.sh
```

### End-to-end browser test

1. Paste your API token in the connection form (not saved to local storage).
2. Click **Load example**, or upload `examples/dashboard-sample.py`.
3. Select `python-basic`, CPU 2, 512 MB RAM and a 2-minute timeout.
4. Submit and watch the job move through `QUEUED`, `DISPATCHED`, `RUNNING`, and `SUCCEEDED`.
5. Select the job in **Your jobs** to see captured stdout and stderr; click **Download result ZIP**.
6. Unzip results and confirm `output/squares.csv` exists.

### Limits and security

- The dashboard is served from the Java JAR and talks to the API on the **same origin**. No CORS or additional ports.
- The Java HTTP listener remains **localhost-only**. Do not expose port 8080 to the public internet. Configure
  authorized HTTPS/VPN access and harden sandboxing before remote multiuser deployment.
- Tokens live only in page memory, not browser storage. Refreshing the page requires connecting again.
- Scripts can be uploaded from `.py` files or edited in the browser. The API still expects a single Python
  script embedded in JSON (not a multipart file upload). Limit is 64 KiB of source.
- Arguments are entered **one per line**; no shell interpreter, live console, or interactive stdin.
- Files must be written to `/job/output` to be available in the result ZIP.
- Environment options come from `GET /api/environments`. The Phase 4 backend exposes only `python-basic`
  until you configure `COREGRID_GPU_IMAGE` for the GPU image. This project does not automatically download Python dependencies.
- The Docker execution security boundary remains a development prototype for **trusted scripts only**.
- Automatic status polling is every 4 seconds; it refreshes job history and captured logs, not live terminal I/O.
- No cancellation or additional library installation UI yet. Those are separate follow-up features.

### Added project files

```text
src/main/java/com/coregrid/api/
  WebAssetsHandler.java     # Allowlisted, same-origin HTML/CSS/JS file serving
  WebAssetsSelfTest.java    # Offline HTTP test for static assets and CSP
src/main/resources/web/
  index.html                # Dashboard UI
  styles.css                # Responsive styling
  app.js                    # Fetch API + upload + polling + ZIP download
examples/
  dashboard-sample.py
  run-phase5-web-smoke.sh
```
