package com.coregrid.api;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Runs without SQLite or Docker; verifies bundled frontend is served safely. */
public final class WebAssetsSelfTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", new WebAssetsHandler());
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            for (String route : new String[]{"/", "/styles.css", "/app.js"}) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || response.body().length() < 100)
                    throw new AssertionError("Expected bundled resource " + route + " status=200");
                if (!response.headers().firstValue("content-security-policy").orElse("").contains("script-src 'self'"))
                    throw new AssertionError("Missing CSP for " + route);
                System.out.println("PASS: GET " + route);
            }
            for (String route : new String[]{"/private", "/index.html.bak", "/.env"}) {
                var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route)).GET().build();
                var response = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 404) throw new AssertionError("Unexpected resource " + route);
            }
            System.out.println("PASS: unknown assets rejected; CSP included");
        } finally { server.stop(0); }
    }
}
