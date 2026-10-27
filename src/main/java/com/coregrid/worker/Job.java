package com.coregrid.worker;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** One submitted execution, with a single transition to a terminal state. */
public final class Job {
    private final String id;
    private final Path directory;
    private final int cpuCores;
    private final boolean requiresGpu;
    private final String environment;
    private final List<String> arguments;
    private final int memoryMb;
    private final int timeoutSeconds;
    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.QUEUED);
    private final AtomicBoolean submitted = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final CompletableFuture<ExecutionResult> completion = new CompletableFuture<>();

    public Job(Path directory, int cpuCores, boolean requiresGpu) {
        this(UUID.randomUUID().toString().substring(0, 8), directory, cpuCores, requiresGpu);
    }

    public Job(String id, Path directory, int cpuCores, boolean requiresGpu) {
        this(id, directory, cpuCores, requiresGpu, requiresGpu ? "pytorch-gpu" : "python-basic",
                List.of(), 512, 120);
    }

    public Job(String id, Path directory, int cpuCores, boolean requiresGpu, String environment,
               List<String> arguments, int memoryMb, int timeoutSeconds) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,40}"))
            throw new IllegalArgumentException("Invalid job ID");
        if (cpuCores < 1 || cpuCores > 4) throw new IllegalArgumentException("CPU request outside 1..4");
        if (!List.of("python-basic", "machine-learning", "pytorch-gpu").contains(environment))
            throw new IllegalArgumentException("Environment not permitted");
        if (memoryMb < 256 || memoryMb > 4096 || timeoutSeconds < 5 || timeoutSeconds > 1800)
            throw new IllegalArgumentException("Memory or timeout outside allowed range");
        if (arguments == null || arguments.size() > 16 || arguments.stream().anyMatch(a ->
                a == null || a.length() > 200 || a.indexOf('\u0000') >= 0))
            throw new IllegalArgumentException("Invalid Python arguments");
        if (requiresGpu != environment.equals("pytorch-gpu"))
            throw new IllegalArgumentException("GPU flag must match pytorch-gpu environment");
        this.id = id;
        this.directory = directory.toAbsolutePath().normalize();
        this.cpuCores = cpuCores;
        this.requiresGpu = requiresGpu;
        this.environment = environment;
        this.arguments = List.copyOf(arguments);
        this.memoryMb = memoryMb;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String id() { return id; }
    public Path directory() { return directory; }
    public int cpuCores() { return cpuCores; }
    public boolean requiresGpu() { return requiresGpu; }
    public String environment() { return environment; }
    public List<String> arguments() { return arguments; }
    public int memoryMb() { return memoryMb; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public JobStatus status() { return status.get(); }
    public CompletableFuture<ExecutionResult> completion() { return completion; }

    public boolean cancellationRequested() { return cancelRequested.get(); }
    /** Queued jobs finish immediately; running jobs finish after their runner stops. */
    boolean requestCancel() {
        while (true) {
            JobStatus current = status.get();
            if (current == JobStatus.CANCELED) return true;
            if (current != JobStatus.QUEUED && current != JobStatus.RUNNING) return false;
            cancelRequested.set(true);
            if (current == JobStatus.RUNNING) return true;
            if (status.compareAndSet(JobStatus.QUEUED, JobStatus.CANCELED)) {
                completion.completeExceptionally(new java.util.concurrent.CancellationException("Canceled while queued"));
                return true;
            }
        }
    }
    void finishCanceled(ExecutionResult result) {
        if (status.compareAndSet(JobStatus.RUNNING, JobStatus.CANCELED)) completion.complete(result);
    }

    boolean markSubmitted() { return submitted.compareAndSet(false, true); }
    boolean start() { return status.compareAndSet(JobStatus.QUEUED, JobStatus.RUNNING); }
    void finish(JobStatus finalStatus, ExecutionResult result) {
        if (finalStatus != JobStatus.SUCCEEDED && finalStatus != JobStatus.FAILED
                && finalStatus != JobStatus.TIMED_OUT)
            throw new IllegalArgumentException("Not terminal: " + finalStatus);
        if (cancelRequested.get()) { finishCanceled(result); return; }
        if (status.compareAndSet(JobStatus.RUNNING, finalStatus)) completion.complete(result);
    }
    void fail(Throwable cause) {
        if (cancelRequested.get() && status.get() == JobStatus.RUNNING) {
            finishCanceled(new ExecutionResult(130, false, null, null, directory.resolve("output")));
            return;
        }
        while (true) {
            JobStatus before = status.get();
            if (before != JobStatus.QUEUED && before != JobStatus.RUNNING) return;
            if (status.compareAndSet(before, JobStatus.FAILED)) {
                completion.completeExceptionally(cause);
                return;
            }
        }
    }
}
