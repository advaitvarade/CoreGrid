package com.coregrid.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Docker execution with container lifecycle control; intended for trusted development workloads. */
public final class DockerJobRunner implements JobRunner {
    private static final String INSTANCE = System.getenv().getOrDefault("COREGRID_INSTANCE_ID", "local");
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    private static final class Active {
        final String containerName;
        volatile boolean canceled;
        volatile boolean created;
        volatile Process process;
        Active(String containerName) { this.containerName = containerName; }
    }

    public static void main(String[] args) throws Exception {
        Path dir = args.length == 0 ? Path.of("jobs", "job-001") : Path.of(args[0]);
        ExecutionResult result = new DockerJobRunner().execute(new Job(dir, 2, false));
        System.out.println("Job process exit code: " + result.exitCode());
    }

    @Override public ExecutionResult execute(Job job) throws IOException, InterruptedException {
        Path root = job.directory();
        Path input = root.resolve("input");
        Path output = root.resolve("output");
        Path logs = root.resolve("logs");
        ProjectLayout.Selection selection = ProjectLayout.select(input);
        Files.createDirectories(output);
        Files.createDirectories(logs);
        int uid = ((Number) Files.getAttribute(output, "unix:uid")).intValue();
        int gid = ((Number) Files.getAttribute(output, "unix:gid")).intValue();
        Path stdout = logs.resolve("stdout.log");
        Path stderr = logs.resolve("stderr.log");
        Files.writeString(stdout, "");
        Files.writeString(stderr, "");
        EnvironmentCatalog.Environment environment = new EnvironmentCatalog().require(job.environment());
        if (environment.gpu() != job.requiresGpu()) throw new IllegalArgumentException("GPU mismatch");
        String containerName = "coregrid-" + job.id() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Active handle = new Active(containerName);
        if (active.putIfAbsent(job.id(), handle) != null)
            throw new IllegalArgumentException("Job already running: " + job.id());

        List<String> command = new ArrayList<>(List.of(
                "docker", "create", "--pull=never", "--name", containerName,
                "--label", "org.coregrid.managed=true",
                "--label", "org.coregrid.instance=" + INSTANCE,
                "--network", "none", "--cpus", Integer.toString(job.cpuCores()),
                "--memory", job.memoryMb() + "m", "--memory-swap", job.memoryMb() + "m",
                "--pids-limit", "64", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--user", uid + ":" + gid,
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m,mode=1777",
                "--env", "HOME=/tmp", "--env", "PYTHONDONTWRITEBYTECODE=1",
                "--mount", "type=bind,src=" + input + ",dst=/job/input,readonly",
                "--mount", "type=bind,src=" + output + ",dst=/job/output",
                "--workdir", selection.containerWorkingDir()));
        if (selection.repository()) command.addAll(List.of("--env", "PYTHONPATH=/job/input/repo"));
        if (job.requiresGpu()) command.addAll(List.of("--gpus", "device=0"));
        command.addAll(List.of(environment.image(), "python", "-B", "-u", selection.containerScript()));
        command.addAll(job.arguments());

        try {
            if (job.cancellationRequested()) return canceledResult(stdout, stderr, output);
            Process creation = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(stderr.toFile()).start();
            boolean createdOnTime = creation.waitFor(30, TimeUnit.SECONDS);
            if (!createdOnTime) {
                creation.destroyForcibly();
                return new ExecutionResult(124, true, stdout, stderr, output);
            }
            if (creation.exitValue() != 0)
                return new ExecutionResult(creation.exitValue(), false, stdout, stderr, output);
            synchronized (handle) {
                handle.created = true;
                if (handle.canceled || job.cancellationRequested()) return canceledResult(stdout, stderr, output);
                // docker start --attach provides stdout/stderr without creating a console.
                handle.process = new ProcessBuilder("docker", "start", "--attach", containerName)
                        .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            }
            boolean finished = handle.process.waitFor(job.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                removeContainer(containerName);
                handle.process.destroyForcibly();
                return new ExecutionResult(124, true, stdout, stderr, output);
            }
            return new ExecutionResult(handle.process.exitValue(), false, stdout, stderr, output);
        } catch (InterruptedException e) {
            removeContainer(containerName);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // Removal is attempted even if create/start fails, the client disconnects or cancellation races.
            removeContainer(containerName);
            active.remove(job.id(), handle);
        }
    }

    @Override public void cancel(String jobId) {
        Active handle = active.get(jobId);
        if (handle == null) {
            // The scheduler may cancel just before execute() registers its Docker handle.
            // execute() will observe job.cancellationRequested() when it starts.
            System.out.println("Docker cancellation pending (container not started): " + jobId);
            return;
        }
        final boolean containerCreated;
        final Process attachedClient;
        synchronized (handle) {
            handle.canceled = true;
            containerCreated = handle.created;
            attachedClient = handle.process;
        }
        // Stopping the container and stopping `docker start --attach` are different
        // operations. With Docker Desktop/WSL the attached CLI can remain blocked
        // after the container is removed; unblock the runner's waitFor explicitly.
        if (containerCreated) removeContainer(handle.containerName);
        if (attachedClient != null && attachedClient.isAlive()) {
            attachedClient.destroy();
            try {
                if (!attachedClient.waitFor(2, TimeUnit.SECONDS)) {
                    attachedClient.destroyForcibly();
                    attachedClient.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                attachedClient.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("Docker cancel processed: " + jobId);
    }

    private static ExecutionResult canceledResult(Path stdout, Path stderr, Path output) {
        return new ExecutionResult(130, false, stdout, stderr, output);
    }

    private static void removeContainer(String name) {
        try {
            Process p = new ProcessBuilder("docker", "rm", "-f", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (IOException | InterruptedException e) {
            System.err.println("WARNING: verify Docker cleanup for " + name + ": " + e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Cleanup after a previous worker JVM crashed; only affects this CoreGrid instance's containers. */
    public static void cleanupOrphanedContainers() {
        try {
            Process ps = new ProcessBuilder("docker", "ps", "-aq",
                    "--filter", "label=org.coregrid.managed=true",
                    "--filter", "label=org.coregrid.instance=" + INSTANCE)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String ids = new String(ps.getInputStream().readAllBytes());
            if (!ps.waitFor(10, TimeUnit.SECONDS) || ps.exitValue() != 0) return;
            for (String id : ids.split("\\R")) {
                if (id.matches("[a-f0-9]{12,64}")) {
                    System.out.println("Removing orphaned CoreGrid container " + id.substring(0, 12));
                    removeContainer(id);
                }
            }
        } catch (Exception e) {
            System.err.println("WARNING: orphan container scan failed: " + e);
        }
    }
}
