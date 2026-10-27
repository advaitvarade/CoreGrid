package com.coregrid.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Docker-free test of queued and running cancellation, without a race on CPU permits. */
public final class CancellationSelfTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("coregrid-cancel-test-");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        JobRunner fake = new JobRunner() {
            @Override public ExecutionResult execute(Job job) throws Exception {
                started.countDown();
                stopped.await(3, TimeUnit.SECONDS);
                return new ExecutionResult(stop.get() ? 130 : 0, false, null, null, root);
            }
            @Override public void cancel(String jobId) { stop.set(true); stopped.countDown(); }
        };
        ResourceManager resources = new ResourceManager(1, 0);
        try (JobScheduler scheduler = new JobScheduler(1, resources, fake)) {
            Job running = new Job("running-1", root.resolve("a"), 1, false);
            Job queued = new Job("queued-1", root.resolve("b"), 1, false);
            scheduler.submit(running);
            if (!started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Did not start running job");
            scheduler.submit(queued);
            if (!scheduler.cancel(queued.id())) throw new AssertionError("Queued cancellation rejected");
            if (queued.status() != JobStatus.CANCELED) throw new AssertionError("Queued not canceled");
            if (!scheduler.cancel(running.id())) throw new AssertionError("Running cancellation rejected");
            running.completion().handle((r, e) -> null).get(4, TimeUnit.SECONDS);
            if (running.status() != JobStatus.CANCELED) throw new AssertionError("Running not canceled");
        }
        if (resources.availableCpuPermits() != 1) throw new AssertionError("Leaked scheduling permit");
        System.out.println("PASS: queued job cancellation");
        System.out.println("PASS: running job cancellation");
        System.out.println("PASS: CPU scheduling permits released");
    }
}
