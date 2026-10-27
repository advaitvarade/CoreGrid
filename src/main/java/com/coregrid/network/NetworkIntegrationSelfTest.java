package com.coregrid.network;

import com.coregrid.worker.ExecutionResult;
import com.coregrid.worker.JobRunner;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** End-to-end TCP test: real Coordinator/Worker socket and scheduler, fake Docker. */
public final class NetworkIntegrationSelfTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("coregrid-network-test-");
        String[] ids = {"test-a", "test-b", "test-c"};
        for (String id : ids) {
            Path input = temp.resolve("remote-" + id).resolve("input");
            Files.createDirectories(input);
            Files.writeString(input.resolve("main.py"), "print('example')\n");
        }
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger gpuRunning = new AtomicInteger();
        AtomicInteger maxGpuConcurrent = new AtomicInteger();
        JobRunner fakeRunner = job -> {
            maxConcurrent.accumulateAndGet(running.incrementAndGet(), Math::max);
            if (job.requiresGpu()) maxGpuConcurrent.accumulateAndGet(gpuRunning.incrementAndGet(), Math::max);
            try {
                Thread.sleep(200);
                Path output = job.directory().resolve("output");
                Files.createDirectories(output);
                Files.writeString(output.resolve("result.txt"), "result=" + job.id() + "\n");
                return new ExecutionResult(0, false, null, null, output);
            } finally {
                if (job.requiresGpu()) gpuRunning.decrementAndGet();
                running.decrementAndGet();
            }
        };
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            CompletableFuture<Void> workerDone = CompletableFuture.runAsync(() -> {
                try { new WorkerAgent(temp, "127.0.0.1", port, fakeRunner).run(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            try (Socket socket = server.accept(); WireConnection wire = new WireConnection(socket)) {
                socket.setSoTimeout(10000);
                WireMessage registered = wire.receive();
                if (registered == null || !registered.type().equals("REGISTER")) {
                    throw new AssertionError("Worker did not register");
                }
                wire.send(WireMessage.run(ids[0], 2, true));
                wire.send(WireMessage.run(ids[1], 2, true));
                wire.send(WireMessage.run(ids[2], 2, false));
                Set<String> ended = new HashSet<>();
                while (ended.size() < 3) {
                    WireMessage event = wire.receive();
                    if (event == null) throw new AssertionError("Lost worker connection");
                    if (event.type().equals("FAILED") || event.type().equals("REJECTED")
                            || event.type().equals("TIMED_OUT")) {
                        throw new AssertionError("Job failed: " + event);
                    }
                    if (event.type().equals("SUCCEEDED")) ended.add(event.jobId());
                }
            }
            workerDone.get(5, TimeUnit.SECONDS);
        }
        for (String id : ids) {
            if (!Files.readString(temp.resolve("remote-" + id + "/output/result.txt")).contains(id)) {
                throw new AssertionError("Missing output for " + id);
            }
        }
        if (maxConcurrent.get() > 2 || maxGpuConcurrent.get() > 1) {
            throw new AssertionError("Resource limits violated");
        }
        System.out.println("PASS: worker registration, TCP job dispatch and result events");
        System.out.println("PASS: 3 separate output directories, all jobs completed");
        System.out.println("PASS: at most 2 concurrent jobs and 1 GPU job");
    }
}
