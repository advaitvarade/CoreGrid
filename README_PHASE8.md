# CoreGrid Phase 8 — Public GitHub multi-file Python jobs

This builds on **Phase 7 with the root-cause cancellation fix applied**. Existing single-file jobs, CPU/GPU scheduling, TCP, SQLite, authentication, and dashboard remain.

## New capability

- New endpoint `POST /api/jobs/repository` accepts a public `https://github.com/owner/repo` URL, optional branch/tag, Python entry-point path, and the same CPU/GPU settings.
- Coordinator runs shallow Git clone into its private job input directory and resolves the exact HEAD commit.
- The HTTP request can take up to ~50 seconds for cloning. Job enters the regular queue only *after* the clone and entry-point validation succeed.
- The worker uses the existing Docker runner with a read-only project mount; module imports inside the repository work, and the process working directory is repository root.
- `GET /api/jobs/{id}/source` shows the recorded repository URL, selected ref, commit SHA, and entry point. Ownership checks apply.
- Outputs **must** go into `/job/output` to appear in the downloadable ZIP.

## Prerequisites

Ubuntu WSL2, Docker, Java 21, Maven, Git CLI: `git --version`. Keep using the locally cached Docker images from Phase 6.

## Upgrade

1. Stop the coordinator and worker agent.
2. `cp -a ~/projects/CoreGrid/coregrid-worker ~/projects/CoreGrid/coregrid-worker-backup-phase7`
3. Unzip this archive *over* `~/projects/CoreGrid/`. Merge and replace overlapping source files. Existing `data/` and `jobs/` folders are not in the ZIP.
4. Build: `cd ~/projects/CoreGrid/coregrid-worker && mvn clean package`
5. Test Java layout logic:
   `java -cp target/classes com.coregrid.source.Phase8SourceSelfTest`
6. Start coordinator (terminal 1):

```bash
export COREGRID_KEY=$(cat ~/.coregrid-dev-token)
export COREGRID_USERS="advait:$COREGRID_KEY"
export COREGRID_GPU_IMAGE="pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime"
java -jar target/coregrid-worker-0.8.0-SNAPSHOT.jar
```

7. Start worker (terminal 2):

```bash
export COREGRID_GPU_IMAGE="pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime"
java -cp target/coregrid-worker-0.8.0-SNAPSHOT.jar com.coregrid.network.WorkerAgent
```

8. Open the dashboard (your Ubuntu browser if Windows localhost forwarding is still broken): `http://127.0.0.1:8080/`.

## First repo execution test

1. Create an empty **public** GitHub repository under your own account. Copy **only** `examples/repository-demo/main.py` and `helpers.py` to the root. These files have no external package requirements. Commit and push.
2. In the dashboard select **Public GitHub repository**; enter the URL, entry point `main.py`, environment `python-basic`, CPU 2, RAM 512 MB, GPU off, timeout 120 seconds.
3. Submit. When it reports `SUCCEEDED`, inspect stdout for `Multifile GitHub project finished` and download output ZIP. Look for `output/squares.csv`.

Or test over HTTP:

```bash
export COREGRID_KEY=$(cat ~/.coregrid-dev-token)
export COREGRID_TEST_REPO_URL=https://github.com/YOURNAME/YOUR_PUBLIC_REPO
bash examples/run-phase8-repo-smoke.sh
```

### Example request

`POST /api/jobs/repository`, header `Authorization: Bearer <token>`, JSON:

```json
{
  "name": "Train model",
  "repositoryUrl": "https://github.com/owner/repo",
  "ref": "main",
  "entryPoint": "src/train.py",
  "environment": "pytorch-gpu",
  "gpu": true,
  "cpu": 2,
  "memoryMb": 4096,
  "timeoutSeconds": 300,
  "args": ["--epochs", "10"]
}
```

## Phase 8 v1 boundaries (important)

- Only public GitHub repositories; no private repo credentials, submodules, Git LFS content, ZIP upload, or automatic `pip install`.
- Installed Python packages come from the **selected approved Docker image**. Internal files (`model.py`, `utils/`, etc.) are usable through imports automatically.
- HTTPS Git clone is performed by the coordinator, not an isolated fetch service. Clones have a 50s timeout, and checked-out trees are limited to 25 MB/1000 files and cannot contain symlinks. **These checks run after Git download** and therefore do not bound network transfer or disk use during cloning. Clone preparation must be hardened and separately sandboxed before permitting arbitrary, untrusted repositories on a shared lab PC.
- Containers remain subject to the Phase 7 security limitations, particularly privileged GPU runtime, and are **not** a complete sandbox for hostile code. Do not expose the current development server publicly.
- The coordinator and worker still share local storage and communicate on the loopback TCP port. The Linux lab deployment requires its own access/security review.
- For predictable results, choose a specific branch/tag that doesn't move or implement future commit-SHA checkout. The current run **records** the exact commit but does not accept an arbitrary SHA as a checkout target.
- Job output files must be placed in `/job/output`. Ordinary files written under the cloned repository path will fail because it is mounted read-only.
