# CoreGrid Phase 7 cancellation fix

Observed symptom: `POST /api/jobs/{id}/cancel` returns `CANCELING` but the job does not reach `CANCELED` within the smoke-test polling window.

## What changed

- `DockerJobRunner.cancel`: after requesting `docker rm -f` for the active container, also terminates the `docker start --attach` client process that the executor thread is waiting for. This prevents an attached Docker CLI from remaining blocked after container termination. It is guarded against cancellation racing with container creation/startup.
- `WorkerAgent`: logs when a TCP `CANCEL_JOB` request is received and whether the worker scheduler accepted it.
- `WorkerHub`: logs TCP `CANCEL_ACK` and whether the final `CANCELED` event updated the persisted job state.

## Install in WSL Ubuntu

1. Stop the Coordinator and Worker Agent with Ctrl+C. Check any old container associated with the stuck job using `docker ps -a --filter 'name=coregrid-c0cfa456'`. Only after confirming it belongs to CoreGrid, an administrator may remove it with `docker rm -f <exact-container-name>`.
2. Back up the working source: `cd ~/projects/CoreGrid && cp -a coregrid-worker coregrid-worker-backup-phase7-cancel`.
3. Extract this ZIP into `~/projects/CoreGrid`, merging the `coregrid-worker/` directory and replacing the **three Java source files** from this patch. Do not overwrite the `data/` directory or token files.
4. Rebuild: `cd ~/projects/CoreGrid/coregrid-worker && mvn clean package`.
5. Start both Phase 7 JVMs using `target/coregrid-worker-0.7.0-SNAPSHOT.jar` and the same environment variables as previously configured.
6. Test in a third terminal: `export COREGRID_KEY=$(cat ~/.coregrid-dev-token)` followed by `bash examples/run-phase7-cancel-smoke.sh`.

## Diagnostics if it still stalls

Look for these lines in the Worker terminal:

- `CANCEL_JOB received: <job-id>` (worker received cancellation)
- `Docker cancel processed: <job-id>` (Docker termination requested and attached client unblocked)
- `CANCEL_JOB acknowledged: <job-id> accepted=true` (worker scheduler accepted cancellation)

And in the Coordinator terminal:

- `Worker cancel acknowledgment: <job-id> / Cancellation requested`
- `Worker confirmed canceled: <job-id> updated=true`

If the last confirmation is missing, collect the two Java terminal logs and `docker ps -a --filter 'name=coregrid-<job-id-prefix>'` before making further changes.

## Test status

Core Java compilation of worker/network classes and WorkerHub succeeded. The existing scheduler and TCP tests passed. A deterministic fake-Docker test where `docker start --attach` blocks for 90 seconds verified that the patched Java worker can complete cancellation promptly. Full Docker Desktop end-to-end verification must run on your WSL machine.

## Safety scope

This is a targeted cancellation reliability fix, **not** a production-grade sandbox guarantee. If `docker rm -f` fails because the Docker daemon is unavailable, an operator must still inspect the container; do not treat a `CANCELED` status alone as proof that no orphaned process exists.
