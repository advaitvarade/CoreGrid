package com.coregrid.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Phase 2 demo: three submissions, two execution threads, queue and resource budget. */
public final class CoreGridMain {
    private static Job createJob(String template, int cpuCores) throws Exception {
        Path dir = Path.of("jobs", "run-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir.resolve("input"));
        Files.copy(Path.of("samples", template), dir.resolve("input").resolve("main.py"),
                StandardCopyOption.REPLACE_EXISTING);
        return new Job(dir, cpuCores, false);
    }

    public static void main(String[] args) throws Exception {
        ResourceManager resources = new ResourceManager(4, 1);
        try (JobScheduler scheduler = new JobScheduler(2, resources, new DockerJobRunner())) {
            List<Job> jobs = List.of(
                    createJob("task_a.py", 2),
                    createJob("task_b.py", 2),
                    createJob("task_c.py", 2)
            );
            List<CompletableFuture<ExecutionResult>> results =
                    jobs.stream().map(scheduler::submit).toList();
            System.out.println("Submitted three CPU jobs; only two run at once.\n");
            for (int i = 0; i < jobs.size(); i++) {
                Job job = jobs.get(i);
                try {
                    ExecutionResult result = results.get(i).join();
                    System.out.printf("Result %s: %s, exit=%d, output=%s%n",
                            job.id(), job.status(), result.exitCode(), result.output());
                } catch (java.util.concurrent.CompletionException e) {
                    System.err.printf("Result %s: %s, error=%s%n", job.id(), job.status(), e.getCause());
                }
            }
        }
    }
}
