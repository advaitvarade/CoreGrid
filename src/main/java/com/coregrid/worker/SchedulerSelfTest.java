package com.coregrid.worker;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** No Docker required: verifies bounded concurrency and GPU exclusivity. */
public final class SchedulerSelfTest {
    public static void main(String[] args) {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger gpuActive = new AtomicInteger();
        AtomicInteger maxGpuActive = new AtomicInteger();

        JobRunner fakeRunner = job -> {
            int running = active.incrementAndGet();
            maxActive.accumulateAndGet(running, Math::max);
            if (job.requiresGpu()) {
                int gpu = gpuActive.incrementAndGet();
                maxGpuActive.accumulateAndGet(gpu, Math::max);
            }
            try {
                Thread.sleep(300);
                return new ExecutionResult(0, false, null, null, null);
            } finally {
                if (job.requiresGpu()) gpuActive.decrementAndGet();
                active.decrementAndGet();
            }
        };

        ResourceManager budget = new ResourceManager(4, 1);
        List<Job> jobs = List.of(
                new Job(Path.of("fake1"), 2, false),
                new Job(Path.of("fake2"), 2, false),
                new Job(Path.of("fake3"), 2, true),
                new Job(Path.of("fake4"), 2, true)
        );
        try (JobScheduler scheduler = new JobScheduler(4, budget, fakeRunner)) {
            List<CompletableFuture<ExecutionResult>> futures =
                    jobs.stream().map(scheduler::submit).toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }
        if (maxActive.get() != 2) throw new AssertionError("Expected two jobs concurrently, got " + maxActive);
        if (maxGpuActive.get() != 1) throw new AssertionError("GPU reservation overlap detected");
        if (jobs.stream().anyMatch(j -> j.status() != JobStatus.SUCCEEDED)) {
            throw new AssertionError("A job failed unexpectedly");
        }
        if (budget.availableCpuPermits() != 4) throw new AssertionError("CPU permits leaked");
        System.out.println("PASS: max simultaneous jobs = " + maxActive.get());
        System.out.println("PASS: max GPU reservations = " + maxGpuActive.get());
        System.out.println("PASS: all jobs succeeded; resource permits returned");
    }
}
