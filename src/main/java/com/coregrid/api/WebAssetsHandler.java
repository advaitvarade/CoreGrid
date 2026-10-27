package com.coregrid.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;

/** Serves only three read-only, bundled assets; no filesystem browsing or template execution. */
public final class WebAssetsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String path = ex.getRequestURI().getPath();
            String filename;
            String contentType;
            switch (path) {
                case "/", "/index.html" -> { filename = "index.html"; contentType = "text/html; charset=utf-8"; }
                case "/styles.css" -> { filename = "styles.css"; contentType = "text/css; charset=utf-8"; }
                case "/app.js" -> { filename = "app.js"; contentType = "text/javascript; charset=utf-8"; }
                default -> { ex.sendResponseHeaders(404, -1); return; }
            }
            byte[] bytes;
            try (InputStream resource = WebAssetsHandler.class.getResourceAsStream("/web/" + filename)) {
                if (resource == null) { ex.sendResponseHeaders(404, -1); return; }
                bytes = resource.readAllBytes();
            }
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "DENY");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            ex.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'self'; style-src 'self'; "
                  + "connect-src 'self'; img-src 'self' blob:; font-src 'self'; "
                  + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
            if (method.equals("HEAD")) ex.sendResponseHeaders(200, -1);
            else {
                ex.sendResponseHeaders(200, bytes.length);
                try (var out = ex.getResponseBody()) { out.write(bytes); }
            }
        } finally {
            ex.close();
        }
    }

}
