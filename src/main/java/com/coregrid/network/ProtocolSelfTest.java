package com.coregrid.network;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;

public final class ProtocolSelfTest {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<WireMessage> received = new CompletableFuture<>();
            Thread sender = Thread.ofPlatform().start(() -> {
                try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                     WireConnection conn = new WireConnection(s)) {
                    conn.send(WireMessage.run("test-1", 2, true));
                    conn.send(WireMessage.simple("REGISTER", "", "probe"));
                } catch (Exception e) { received.completeExceptionally(e); }
            });
            try (Socket socket = server.accept(); WireConnection conn = new WireConnection(socket)) {
                WireMessage first = conn.receive();
                WireMessage second = conn.receive();
                if (!"RUN_JOB".equals(first.type()) || !"test-1".equals(first.jobId())
                        || first.cpu() != 2 || !first.gpu() || !"REGISTER".equals(second.type())) {
                    throw new AssertionError("TCP protocol lost/misparsed a field");
                }
                System.out.println("PASS: framed messages round-trip over localhost TCP");
            }
            sender.join();
        }
    }
}
