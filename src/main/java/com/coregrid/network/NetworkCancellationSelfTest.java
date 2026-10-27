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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Docker-free TCP integration test for running and queued job cancellation. */
public final class NetworkCancellationSelfTest {
    public static void main(String[] args) throws Exception {
        Path jobs = Files.createTempDirectory("coregrid-tcp-cancel-");
        for (String id : new String[]{"cancel-running", "cancel-queued"}) {
            Path input = jobs.resolve("remote-" + id).resolve("input");
            Files.createDirectories(input);
            Files.writeString(input.resolve("main.py"), "print('test')");
        }
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicBoolean wasCanceled = new AtomicBoolean();
        JobRunner fake = new JobRunner() {
            public ExecutionResult execute(com.coregrid.worker.Job job) throws Exception {
                started.countDown();
                stop.await(5, TimeUnit.SECONDS);
                return new ExecutionResult(wasCanceled.get() ? 130 : 0, false, null, null, jobs);
            }
            public void cancel(String id) { wasCanceled.set(true); stop.countDown(); }
        };
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> agent = CompletableFuture.runAsync(() -> {
                try { new WorkerAgent(jobs, "127.0.0.1", server.getLocalPort(), fake).run(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            try (Socket socket = server.accept(); WireConnection conn = new WireConnection(socket)) {
                socket.setSoTimeout(8000);
                if (!conn.receive().type().equals("REGISTER")) throw new AssertionError("No registration");
                conn.send(WireMessage.run("cancel-running", 4, false));
                if (!started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Not running");
                conn.send(WireMessage.run("cancel-queued", 4, false));
                conn.send(WireMessage.simple("CANCEL_JOB", "cancel-queued", ""));
                conn.send(WireMessage.simple("CANCEL_JOB", "cancel-running", ""));
                Set<String> done = new HashSet<>();
                while (done.size() < 2) {
                    WireMessage msg = conn.receive();
                    if (msg == null) throw new AssertionError("Lost connection");
                    if (msg.type().equals("CANCELED")) done.add(msg.jobId());
                    if (msg.type().equals("SUCCEEDED") || msg.type().equals("FAILED") || msg.type().equals("REJECTED"))
                        throw new AssertionError("Unexpected status: " + msg);
                }
            }
            if (!wasCanceled.get()) {
                throw new AssertionError("CANCEL_JOB did not invoke the real JobRunner.cancel()");
            }
            agent.get(8, TimeUnit.SECONDS);
        }
        System.out.println("PASS: TCP cancellation forwarded to real runner for running job");
        System.out.println("PASS: TCP cancellation of running and queued jobs");
    }
}
