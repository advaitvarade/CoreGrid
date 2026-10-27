package com.coregrid.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Local-only Phase 3 coordinator demo: dispatches three sample jobs over TCP. */
public final class CoordinatorMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        Path root = Path.of(System.getenv().getOrDefault("COREGRID_DATA_DIR", "data"), "jobs")
                .toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("Coordinator listening on 127.0.0.1:" + port);
            System.out.println("Start WorkerAgent in a SECOND Ubuntu terminal.");
            try (Socket socket = server.accept(); WireConnection connection = new WireConnection(socket)) {
                WireMessage reg = connection.receive();
                if (reg == null || !reg.type().equals("REGISTER")) {
                    throw new IOException("First message must be REGISTER");
                }
                System.out.println("Worker registered: " + reg.detail());
                List<String> samples = List.of("task_a.py", "task_b.py", "task_c.py");
                Set<String> pending = new HashSet<>();
                for (String sample : samples) {
                    String id = UUID.randomUUID().toString().substring(0, 8);
                    Path dir = root.resolve("remote-" + id);
                    Files.createDirectories(dir.resolve("input"));
                    Files.copy(Path.of("samples", sample), dir.resolve("input/main.py"),
                            StandardCopyOption.REPLACE_EXISTING);
                    pending.add(id);
                    connection.send(WireMessage.run(id, 2, false));
                    System.out.println("SENT RUN_JOB " + id + " sample=" + sample);
                }
                while (!pending.isEmpty()) {
                    WireMessage event = connection.receive();
                    if (event == null) throw new IOException("Worker disconnected before completing all jobs: " + pending);
                    System.out.printf("EVENT %-10s job=%-8s exit=%d %s%n", event.type(), event.jobId(),
                            event.exitCode(), event.detail());
                    if (Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "REJECTED").contains(event.type())) {
                        pending.remove(event.jobId());
                    }
                }
                System.out.println("All jobs finished. Inspect data/jobs/remote-*/output and logs.");
            }
        }
    }
}
