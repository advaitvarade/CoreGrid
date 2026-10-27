package com.coregrid.worker;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Producer-consumer scheduler: many submitters, a bounded number of workers. */
public final class JobScheduler implements AutoCloseable {
    private final BlockingQueue<Job> waiting = new LinkedBlockingQueue<>();
    private final ExecutorService workers;
    private final ResourceManager resources;
    private final JobRunner runner;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Map<String, Job> known = new ConcurrentHashMap<>();

    public JobScheduler(int maxWorkerThreads, ResourceManager resources, JobRunner runner) {
        if (maxWorkerThreads < 1) throw new IllegalArgumentException("At least one worker required");
        this.resources = resources;
        this.runner = runner;
        workers = Executors.newFixedThreadPool(maxWorkerThreads);
        for (int i = 0; i < maxWorkerThreads; i++) workers.execute(this::workerLoop);
    }

    public synchronized CompletableFuture<ExecutionResult> submit(Job job) {
        if (!accepting.get()) throw new IllegalStateException("Scheduler is closed");
        if (job.status() != JobStatus.QUEUED || !job.markSubmitted())
            throw new IllegalArgumentException("Job already submitted");
        if (known.putIfAbsent(job.id(), job) != null)
            throw new IllegalArgumentException("Duplicate job ID");
        job.completion().whenComplete((ignored, error) -> known.remove(job.id(), job));
        log(job, "QUEUED");
        if (!waiting.offer(job)) { known.remove(job.id()); throw new IllegalStateException("Queue is full"); }
        return job.completion();
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            Job job;
            try {
                job = waiting.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try (ResourceManager.Reservation reserved = resources.acquire(job)) {
                if (!job.start()) continue;
                log(job, "RUNNING" + (job.requiresGpu() ? " [GPU reserved]" : ""));
                ExecutionResult result = runner.execute(job);
                if (job.cancellationRequested()) {
                    job.finishCanceled(result);
                    log(job, "CANCELED");
                    continue;
                }
                JobStatus status = result.timedOut() ? JobStatus.TIMED_OUT
                        : result.exitCode() == 0 ? JobStatus.SUCCEEDED : JobStatus.FAILED;
                job.finish(status, result);
                log(job, status + " (exit " + result.exitCode() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.fail(e);
                log(job, "INTERRUPTED");
                break;
            } catch (Exception e) {
                job.fail(e);
                log(job, "FAILED: " + e.getMessage());
            }
        }
    }

    public boolean cancel(String jobId) {
        Job job = known.get(jobId);
        if (job == null || !job.requestCancel()) return false;
        waiting.remove(job);
        if (job.status() == JobStatus.RUNNING) {
            try { runner.cancel(jobId); }
            catch (Exception e) { System.err.println("Cancel request failed for " + jobId + ": " + e); }
        }
        return true;
    }

    public int queuedCount() { return waiting.size(); }

    private static void log(Job job, String message) {
        synchronized (System.out) {
            System.out.printf("[%s] Job %s: %s%n", LocalTime.now().withNano(0), job.id(), message);
        }
    }

    @Override public synchronized void close() {
        if (!accepting.compareAndSet(true, false)) return;
        List<Job> unstarted = new ArrayList<>();
        waiting.drainTo(unstarted);
        for (Job job : unstarted) job.requestCancel();
        for (Job job : List.copyOf(known.values())) cancel(job.id());
        workers.shutdownNow();
    }
}
