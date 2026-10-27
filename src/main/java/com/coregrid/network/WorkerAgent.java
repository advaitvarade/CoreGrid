package com.coregrid.network;

import com.coregrid.worker.DockerJobRunner;
import com.coregrid.worker.ExecutionResult;
import com.coregrid.worker.Job;
import com.coregrid.worker.JobRunner;
import com.coregrid.worker.JobScheduler;
import com.coregrid.worker.ResourceManager;
import com.coregrid.worker.EnvironmentCatalog;
import com.coregrid.worker.GpuDetector;
import com.coregrid.worker.ProjectLayout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

/** Worker-side TCP agent. Uses Phase 2 JobScheduler and real DockerJobRunner. */
public final class WorkerAgent {
    private final Path root;
    private final JobRunner runner;
    private final String host;
    private final int port;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public WorkerAgent(Path root, String host, int port, JobRunner runner) {
        this.root = root.toAbsolutePath().normalize();
        this.host = host;
        this.port = port;
        this.runner = runner;
    }

    public void run() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            try (WireConnection connection = new WireConnection(socket);
                 JobScheduler scheduler = new JobScheduler(2, new ResourceManager(4, 1),
                        new JobRunner() {
                            @Override public ExecutionResult execute(Job job) throws Exception {
                                send(connection, WireMessage.simple("STARTED", job.id(), ""));
                                return runner.execute(job);
                            }

                            @Override public void cancel(String jobId) throws Exception {
                                // Forward cancellation to the actual DockerJobRunner.
                                // A lambda only implements execute(); the default cancel() is a no-op.
                                runner.cancel(jobId);
                            }
                        })) {
                EnvironmentCatalog catalog = new EnvironmentCatalog();
                GpuDetector.GpuInfo detected = GpuDetector.detect();
                boolean imageReady = catalog.contains("pytorch-gpu") &&
                        GpuDetector.localImagePresent(catalog.require("pytorch-gpu").image());
                boolean gpuReady = detected.present() && imageReady &&
                        GpuDetector.dockerCudaReady(catalog.require("pytorch-gpu").image());
                System.out.println("GPU on worker: " + detected.description()
                        + " | cached GPU image: " + imageReady + " | CUDA container probe: " + gpuReady);
                send(connection, WireMessage.simple("REGISTER", "",
                        "coregrid-worker-v1;gpu=" + (gpuReady ? "1" : "0")));
                while (true) {
                    WireMessage message = connection.receive();
                    if (message == null) break;
                    if (message.type().equals("CANCEL_JOB")) {
                        System.out.println("CANCEL_JOB received: " + message.jobId());
                        boolean accepted = scheduler.cancel(message.jobId());
                        System.out.println("CANCEL_JOB acknowledged: " + message.jobId() + " accepted=" + accepted);
                        send(connection, WireMessage.simple("CANCEL_ACK", message.jobId(),
                                accepted ? "Cancellation requested" : "Already finished or unknown"));
                    } else if (message.type().equals("RUN_JOB")) {
                        handleJob(message, connection, scheduler);
                    } else {
                        send(connection, WireMessage.simple("REJECTED", message.jobId(), "Unsupported command"));
                    }
                }
            }
        }
    }

    private void handleJob(WireMessage message, WireConnection connection, JobScheduler scheduler) {
        String id = message.jobId();
        try {
            if (!id.matches("[a-zA-Z0-9-]{1,40}")) throw new IllegalArgumentException("Invalid job ID");
            if (message.cpu() < 1 || message.cpu() > 4) throw new IllegalArgumentException("CPU request outside 1..4");
            Path directory = root.resolve("remote-" + id).normalize();
            if (!directory.startsWith(root) || !Files.isDirectory(directory.resolve("input"))) {
                throw new IllegalArgumentException("Job input directory not found in shared jobs directory");
            }
            ProjectLayout.select(directory.resolve("input"));
            if (!seen.add(id)) throw new IllegalArgumentException("Duplicate job ID");
            // Validate real Docker images on production runner; fake runner used by socket tests
            // is intentionally independent of local images and GPU hardware.
            if (runner instanceof DockerJobRunner) {
                EnvironmentCatalog.Environment selected = new EnvironmentCatalog().require(message.environment());
                if (selected.gpu() != message.gpu())
                    throw new IllegalArgumentException("GPU flag mismatch");
                if (!GpuDetector.localImagePresent(selected.image()))
                    throw new IllegalArgumentException("Selected Docker image is not cached: " + selected.image());
                if (message.gpu() && !GpuDetector.detect().present())
                    throw new IllegalArgumentException("NVIDIA GPU not available on worker");
            }
            Job job = new Job(id, directory, message.cpu(), message.gpu(), message.environment(),
                    message.arguments(), message.memoryMb(), message.timeoutSeconds());
            // Hold the same lock used by WireConnection.send so ACCEPTED is sent
            // before asynchronous STARTED / completion events for this job.
            synchronized (connection) {
                scheduler.submit(job).whenComplete((result, error) -> {
                    if (job.status() == com.coregrid.worker.JobStatus.CANCELED) {
                        send(connection, WireMessage.outcome("CANCELED", id, 130, "Container stopped or job never started"));
                        return;
                    }
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause() : error;
                        send(connection, WireMessage.simple("FAILED", id, safeText(cause.toString())));
                        return;
                    }
                    String status = result.timedOut() ? "TIMED_OUT" : result.exitCode() == 0 ? "SUCCEEDED" : "FAILED";
                    send(connection, WireMessage.outcome(status, id,
                            result.exitCode(), "See jobs/remote-" + id + "/logs and output"));
                });
                send(connection, WireMessage.simple("ACCEPTED", id, "Queued for execution"));
            }
        } catch (Exception e) {
            send(connection, WireMessage.simple("REJECTED", id, safeText(e.getMessage())));
        }
    }

    private static String safeText(String text) {
        return text == null ? "Unknown error" : text.substring(0, Math.min(1000, text.length()));
    }

    private static void send(WireConnection connection, WireMessage message) {
        try { connection.send(message); }
        catch (IOException e) { System.err.println("Cannot report " + message.type() + ": " + e); }
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        Path dir = Path.of(System.getenv().getOrDefault("COREGRID_DATA_DIR", "data"), "jobs");
        System.out.println("Connecting worker to " + host + ":" + port + " ...");
        Files.createDirectories(dir.getParent());
        try (FileChannel channel = FileChannel.open(dir.getParent().resolve("worker.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another worker is using this data directory");
            DockerJobRunner.cleanupOrphanedContainers();
            new WorkerAgent(dir, host, port, new DockerJobRunner()).run();
            System.out.println("Worker disconnected; scheduler stopped.");
        }
    }
}
