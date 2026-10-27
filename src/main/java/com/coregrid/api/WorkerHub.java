package com.coregrid.api;

import com.coregrid.network.WireConnection;
import com.coregrid.network.WireMessage;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** One local worker at a time. Dispatches queued HTTP jobs using the Phase 3 TCP protocol. */
public final class WorkerHub implements AutoCloseable {
    public record Dispatch(String id, int cpu, boolean gpu, String environment,
                           List<String> args, int memoryMb, int timeoutSeconds) {}
    private final Object activeLock = new Object();
    private final JobRepository repository;
    private final BlockingQueue<Dispatch> queue = new LinkedBlockingQueue<>(100);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final ServerSocket server;
    private WireConnection active;
    private boolean activeGpuReady;
    private final Thread listener;
    private final Thread dispatcher;

    public WorkerHub(int port, JobRepository repository) throws IOException {
        this.repository = repository;
        server = new ServerSocket(port, 2, InetAddress.getLoopbackAddress());
        listener = Thread.ofPlatform().name("tcp-listener").start(this::listen);
        dispatcher = Thread.ofPlatform().name("job-dispatcher").start(this::dispatchLoop);
    }
    public boolean enqueue(Dispatch job) { return queue.offer(job); }
    public boolean connected() { synchronized (activeLock) { return active != null; } }
    public boolean gpuReady() { synchronized (activeLock) { return active != null && activeGpuReady; } }
    public int queuedCount() { return queue.size(); }

    /** Serialize cancellation against dispatch, so CANCEL_JOB cannot overtake RUN_JOB. */
    public String requestCancellation(String id) throws Exception {
        synchronized (activeLock) {
            JobRepository.JobRow row = repository.get(id);
            if (row == null) return "UNKNOWN";
            if (row.status().equals("QUEUED")) {
                if (repository.transition(id, "CANCELED", null, "Canceled before dispatch", "QUEUED")) {
                    queue.removeIf(job -> job.id().equals(id));
                    return "CANCELED";
                }
                row = repository.get(id);
            }
            if (row.status().equals("CANCELING")) return "CANCELING";
            if (row.status().equals("RUNNING") || row.status().equals("DISPATCHED")) {
                if (active == null) {
                    repository.transition(id, "INTERRUPTED", null, "Worker unavailable; outcome unknown",
                            "DISPATCHED", "RUNNING");
                    return "INTERRUPTED";
                }
                if (repository.transition(id, "CANCELING", null, "Cancellation requested",
                        "RUNNING", "DISPATCHED")) {
                    try { active.send(WireMessage.simple("CANCEL_JOB", id, "")); }
                    catch (IOException e) {
                        repository.transition(id, "INTERRUPTED", null,
                                "Cancel delivery failed; worker outcome unknown", "CANCELING");
                        try { active.close(); } catch (IOException ignored) {}
                        return "INTERRUPTED";
                    }
                    return "CANCELING";
                }
            }
            return repository.get(id).status();
        }
    }

    private void listen() {
        while (open.get()) {
            try {
                Socket socket = server.accept();
                Thread.ofPlatform().name("worker-events").start(() -> handle(socket));
            } catch (IOException e) {
                if (open.get()) System.err.println("Worker listener error: " + e.getMessage());
            }
        }
    }
    private void handle(Socket socket) {
        try (socket; WireConnection wire = new WireConnection(socket)) {
            WireMessage register = wire.receive();
            if (register == null || !"REGISTER".equals(register.type())) return;
            synchronized (activeLock) {
                if (active != null) { System.err.println("Rejecting second worker connection"); return; }
                active = wire;
                activeGpuReady = register.detail().contains(";gpu=1");
                currentSocket = socket;
                activeLock.notifyAll();
            }
            System.out.println("Worker registered: " + register.detail());
            WireMessage message;
            while ((message = wire.receive()) != null) receiveEvent(message);
        } catch (Exception e) {
            if (open.get()) System.err.println("Worker connection ended: " + e.getMessage());
        } finally {
            synchronized (activeLock) {
                // A second connection must not invalidate a still-running first worker.
                if (active != null && activeSocketBelongsTo(socket)) {
                    active = null;
                    activeGpuReady = false;
                    try { repository.interruptRunning("Worker disconnected; job outcome uncertain"); }
                    catch (Exception e) { System.err.println("Database error: " + e); }
                    activeLock.notifyAll();
                }
            }
        }
    }
    // WireConnection doesn't expose a socket. Identity is tracked per handler instead.
    private boolean activeSocketBelongsTo(Socket socket) { return currentSocket == socket; }
    private volatile Socket currentSocket;

    private void receiveEvent(WireMessage e) throws Exception {
        switch (e.type()) {
            case "ACCEPTED" -> { /* no state transition needed */ }
            case "CANCEL_ACK" -> System.out.println("Worker cancel acknowledgment: "
                    + e.jobId() + " / " + e.detail());
            case "STARTED" -> repository.transition(e.jobId(), "RUNNING", null, "Worker started", "DISPATCHED");
            case "CANCELED" -> {
                boolean updated = repository.transition(e.jobId(), "CANCELED", 130, e.detail(),
                        "CANCELING", "DISPATCHED", "RUNNING");
                System.out.println("Worker confirmed canceled: " + e.jobId() + " updated=" + updated);
            }
            case "SUCCEEDED", "FAILED", "TIMED_OUT", "REJECTED" ->
                    repository.transition(e.jobId(), e.type(), e.exitCode(), e.detail(), "RUNNING", "DISPATCHED", "CANCELING");
            default -> System.err.println("Ignoring unknown worker event: " + e.type());
        }
    }
    private void dispatchLoop() {
        while (open.get()) {
            Dispatch job;
            try { job = queue.take(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try {
                WireConnection conn;
                synchronized (activeLock) {
                    while (active == null && open.get()) activeLock.wait();
                    if (!open.get()) return;
                    conn = active;
                }
                synchronized (activeLock) {
                    if (!repository.transition(job.id(), "DISPATCHED", null, "Sent to worker", "QUEUED")) continue;
                    try {
                        conn.send(WireMessage.run(job.id(), job.cpu(), job.gpu(), job.environment(),
                                job.args(), job.memoryMb(), job.timeoutSeconds()));
                        System.out.println("RUN_JOB " + job.id());
                    } catch (IOException e) {
                        repository.transition(job.id(), "INTERRUPTED", null, "TCP dispatch failed; no automatic retry", "DISPATCHED");
                        System.err.println("Dispatch failed: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("Dispatcher error: " + e);
                try { repository.transition(job.id(), "INTERRUPTED", null, e.toString(), "QUEUED", "DISPATCHED"); }
                catch (Exception ignored) {}
            }
        }
    }
    @Override public void close() throws Exception {
        if (!open.compareAndSet(true,false)) return;
        server.close();
        synchronized (activeLock) {
            if (active != null) active.close();
            activeLock.notifyAll();
        }
        dispatcher.interrupt();
        listener.interrupt();
    }
}
