package com.coregrid.api;

import com.coregrid.worker.EnvironmentCatalog;
import com.coregrid.source.GitHubSourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Phase 4 Coordinator: loopback-only HTTP + TCP, API-key authentication, SQLite. */
public final class ApiServerMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REQUEST_BYTES = 96 * 1024;
    private static final int MAX_SCRIPT_CHARS = 64 * 1024;
    private final Path jobsRoot;
    private final JobRepository repository;
    private final WorkerHub hub;
    private final Map<String, String> tokens;
    private final EnvironmentCatalog environments = new EnvironmentCatalog();

    private ApiServerMain(Path dataDir, int tcpPort, Map<String, String> tokens) throws Exception {
        Files.createDirectories(dataDir);
        jobsRoot = dataDir.resolve("jobs").toAbsolutePath().normalize();
        Files.createDirectories(jobsRoot);
        repository = new JobRepository(dataDir.resolve("coregrid.db"));
        hub = new WorkerHub(tcpPort, repository);
        this.tokens = tokens;

    }

    public static void main(String[] args) throws Exception {
        String raw = System.getenv("COREGRID_USERS");
        if (raw == null || raw.isBlank()) {
            System.err.println("Set COREGRID_USERS='advait:<long-random-token>' before running the API.");
            System.exit(2);
        }
        Map<String, String> tokens = new HashMap<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.trim().split(":", 2);
            if (parts.length != 2 || !parts[0].matches("[a-zA-Z0-9_-]{1,32}") || parts[1].length() < 16)
                throw new IllegalArgumentException("Invalid COREGRID_USERS entry: use username:token (token >=16 chars)");
            if (tokens.putIfAbsent(parts[1], parts[0]) != null)
                throw new IllegalArgumentException("Duplicate token");
        }
        Path root = Path.of(System.getenv().getOrDefault("COREGRID_DATA_DIR", "data"));
        ApiServerMain api = new ApiServerMain(root, 9090, tokens);
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080), 32);
        http.createContext("/api/", api::route);
        http.createContext("/", new WebAssetsHandler());
        http.setExecutor(Executors.newFixedThreadPool(8));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop(0);
            try { api.hub.close(); } catch (Exception ignored) {}
        }));
        http.start();
        System.out.println("CoreGrid Phase 8 Dashboard: http://127.0.0.1:8080/");
        System.out.println("CoreGrid API: http://127.0.0.1:8080/api/health");
        System.out.println("CoreGrid Phase 4 TCP:  127.0.0.1:9090 (start WorkerAgent in another terminal)");
        System.out.println("Job data: " + api.jobsRoot + " | configured users=" + tokens.size());
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String user = authenticatedUser(ex);
            if (user == null) { reply(ex, 401, Map.of("error", "Missing or invalid bearer token")); return; }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/health") && method.equals("GET")) {
                reply(ex, 200, Map.of("status", "ok", "workerConnected", hub.connected(),
                        "queueDepth", hub.queuedCount(), "gpuReady", hub.gpuReady())); return;
            }
            if (path.equals("/api/environments") && method.equals("GET")) {
                List<Map<String, Object>> images = new ArrayList<>();
                for (EnvironmentCatalog.Environment env : environments.all()) {
                    if (!env.gpu() || hub.gpuReady())
                        images.add(Map.of("id", env.id(), "image", env.image(), "gpu", env.gpu()));
                }
                reply(ex, 200, images); return;
            }
            if (path.equals("/api/jobs/repository") && method.equals("POST")) {
                createJob(ex, user, true); return;
            }
            if (path.equals("/api/jobs")) {
                if (method.equals("POST")) { createJob(ex, user, false); return; }
                if (method.equals("GET")) { reply(ex, 200, repository.list(user)); return; }
            }
            if (path.startsWith("/api/jobs/")) {
                String[] parts = path.substring("/api/jobs/".length()).split("/");
                if (parts.length < 1 || !parts[0].matches("[a-zA-Z0-9-]{1,40}")) {
                    reply(ex, 404, Map.of("error", "Not found")); return;
                }
                JobRepository.JobRow job = repository.get(parts[0]);
                if (job == null || !job.userId().equals(user)) {
                    reply(ex, 404, Map.of("error", "Not found")); return;
                }
                if (method.equals("POST") && parts.length == 2 && parts[1].equals("cancel")) {
                    String status = hub.requestCancellation(job.id());
                    if (List.of("CANCELED", "CANCELING", "INTERRUPTED").contains(status)) {
                        reply(ex, 202, Map.of("id", job.id(), "status", status));
                    } else {
                        reply(ex, 409, Map.of("error", "Job is not cancelable", "status", status));
                    }
                    return;
                }
                if (method.equals("GET") && parts.length == 1) { reply(ex, 200, job); return; }
                if (method.equals("GET") && parts.length == 2 && parts[1].equals("source")) {
                    reply(ex, 200, repository.repositorySource(job.id())); return;
                }
                if (method.equals("GET") && parts.length == 2 && parts[1].equals("logs")) {
                    Path logs = jobDir(job.id()).resolve("logs");
                    reply(ex, 200, Map.of("stdout", readLog(logs.resolve("stdout.log")),
                            "stderr", readLog(logs.resolve("stderr.log")))); return;
                }
                if (method.equals("GET") && parts.length == 2 && parts[1].equals("artifacts")) {
                    if (!List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELED").contains(job.status())) {
                        reply(ex, 409, Map.of("error", "Job has not finished")); return;
                    }
                    downloadZip(ex, job.id()); return;
                }
            }
            reply(ex, 404, Map.of("error", "Unknown endpoint"));
        } catch (BadRequest e) {
            reply(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            System.err.println("HTTP error: " + e);
            reply(ex, 500, Map.of("error", "Server error"));
        } finally {
            ex.close();
        }
    }

    private synchronized void createJob(HttpExchange ex, String user, boolean repositoryJob) throws Exception {
        // Bound each user's live submissions to avoid an unbounded per-user backlog.
        long pending = repository.list(user).stream().filter(j ->
                List.of("QUEUED", "DISPATCHED", "RUNNING", "CANCELING").contains(j.status())).count();
        if (pending >= 8) {
            reply(ex, 429, Map.of("error", "Maximum 8 active or queued jobs per user"));
            return;
        }
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new BadRequest("Content-Type must be application/json");
        byte[] body = ex.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) { reply(ex, 413, Map.of("error", "Request too large")); return; }
        JsonNode root;
        try { root = JSON.readTree(body); }
        catch (Exception e) { throw new BadRequest("Malformed JSON body"); }
        if (root == null || !root.isObject()) throw new BadRequest("JSON object expected");
        String script = null;
        String repoUrl = null, repoRef = null, entryPoint = null;
        if (repositoryJob) {
            if (!root.path("repositoryUrl").isTextual() || !root.path("entryPoint").isTextual())
                throw new BadRequest("repositoryUrl and entryPoint must be strings");
            repoUrl = root.get("repositoryUrl").asText();
            repoRef = root.path("ref").asText("");
            entryPoint = root.get("entryPoint").asText();
            try {
                GitHubSourceProvider.canonicalUrl(repoUrl);
                GitHubSourceProvider.branch(repoRef);
                GitHubSourceProvider.entryPoint(entryPoint);
            } catch (GitHubSourceProvider.InvalidSource e) { throw new BadRequest(e.getMessage()); }
        } else {
            if (!root.path("script").isTextual()) throw new BadRequest("script must be a string");
            script = root.get("script").asText();
            if (script.isBlank() || script.length() > MAX_SCRIPT_CHARS || script.indexOf('\0') >= 0)
                throw new BadRequest("Script must be 1..65536 characters with no null bytes");
        }
        String name = root.path("name").asText("Python job");
        if (name.isBlank() || name.length() > 80) throw new BadRequest("name must be 1..80 characters");
        String environment = root.path("environment").asText("python-basic");
        if (!environments.contains(environment))
            throw new BadRequest("Environment is not configured on this server");
        EnvironmentCatalog.Environment chosen = environments.require(environment);
        if (chosen.gpu() && !hub.gpuReady())
            throw new BadRequest("GPU worker or GPU image is unavailable");
        JsonNode cpuNode = root.path("cpu");
        JsonNode memNode = root.path("memoryMb");
        JsonNode timeoutNode = root.path("timeoutSeconds");
        int cpu = cpuNode.isMissingNode() ? 2 : integer(cpuNode, "cpu", 1, 4);
        int memoryMb = memNode.isMissingNode() ? 512 : integer(memNode, "memoryMb", 256, 4096);
        int timeoutSeconds = timeoutNode.isMissingNode() ? 120 : integer(timeoutNode, "timeoutSeconds", 5, 1800);
        JsonNode gpuNode = root.path("gpu");
        if (!gpuNode.isMissingNode() && !gpuNode.isBoolean()) throw new BadRequest("gpu must be boolean");
        boolean gpu = gpuNode.asBoolean(false);
        if (gpu != chosen.gpu())
            throw new BadRequest("GPU flag must match selected environment");
        List<String> arguments = new ArrayList<>();
        JsonNode args = root.path("args");
        if (!args.isMissingNode()) {
            if (!args.isArray() || args.size() > 16) throw new BadRequest("args must be an array of <=16 strings");
            for (JsonNode item : args) {
                if (!item.isTextual() || item.asText().length() > 200 || item.asText().indexOf('\0') >= 0)
                    throw new BadRequest("Each arg must be a string with <=200 characters");
                arguments.add(item.asText());
            }
        }
        String id = UUID.randomUUID().toString();
        Path dir = jobDir(id);
        Files.createDirectories(dir.resolve("input"));
        Files.createDirectories(dir.resolve("output"));
        Files.createDirectories(dir.resolve("logs"));
        GitHubSourceProvider.Result source = null;
        try {
            if (repositoryJob) {
                try {
                    source = new GitHubSourceProvider().prepare(repoUrl, repoRef, entryPoint, dir.resolve("input"));
                } catch (GitHubSourceProvider.InvalidSource e) {
                    reply(ex, 422, Map.of("error", e.getMessage()));
                    GitHubSourceProvider.deleteTree(dir);
                    return;
                }
            } else {
                Files.writeString(dir.resolve("input/main.py"), script, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
        } catch (Exception e) {
            GitHubSourceProvider.deleteTree(dir);
            throw e;
        }
        String now = Instant.now().toString();
        JobRepository.JobRow record = new JobRepository.JobRow(id,user,name,"QUEUED",cpu,gpu,
                environment,JSON.writeValueAsString(arguments),memoryMb,timeoutSeconds,now,now,null,"");
        repository.create(record);
        if (source != null) repository.recordRepositorySource(id, source.canonicalUrl(),
                source.ref(), source.commit(), source.entryPoint());
        boolean added = hub.enqueue(new WorkerHub.Dispatch(id,cpu,gpu,environment,arguments,memoryMb,timeoutSeconds));
        if (!added) {
            repository.transition(id, "REJECTED", null, "Coordinator queue full", "QUEUED");
            reply(ex, 503, Map.of("error", "Queue full")); return;
        }
        reply(ex, 202, Map.of("id", id, "status", "QUEUED", "statusUrl", "/api/jobs/" + id));
    }
    private static int integer(JsonNode n, String key, int min, int max) throws BadRequest {
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < min || n.asInt() > max)
            throw new BadRequest(key + " must be an integer between " + min + " and " + max);
        return n.asInt();
    }
    private Path jobDir(String id) { return jobsRoot.resolve("remote-" + id); }
    private String authenticatedUser(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String presented = header.substring(7);
        // Fixed-time equality to avoid early-out comparisons; credentials are not logged.
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            if (MessageDigest.isEqual(entry.getKey().getBytes(StandardCharsets.UTF_8),
                    presented.getBytes(StandardCharsets.UTF_8))) return entry.getValue();
        }
        return null;
    }
    private static String readLog(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return "";
        try (var in = Files.newInputStream(path)) {
            return new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
    }
    private static void reply(HttpExchange ex, int code, Object object) throws IOException {
        byte[] b = JSON.writeValueAsBytes(object);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length);
        try (var out = ex.getResponseBody()) { out.write(b); }
    }
    private void downloadZip(HttpExchange ex, String id) throws IOException {
        Path dir = jobDir(id);
        Path output = dir.resolve("output");
        Path logs = dir.resolve("logs");
        List<Path> all = new ArrayList<>();
        for (Path base : List.of(output, logs)) {
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var paths = Files.walk(base)) {
                paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(all::add);
            }
        }
        if (all.size() > 200) { reply(ex, 413, Map.of("error", "Too many result files")); return; }
        long bytes = 0;
        for (Path p : all) {
            bytes += Files.size(p);
            if (bytes > 50L * 1024 * 1024) { reply(ex, 413, Map.of("error", "Results exceed 50 MB")); return; }
        }
        ex.getResponseHeaders().set("Content-Type", "application/zip");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=coregrid-" + id + ".zip");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        try (ZipOutputStream zip = new ZipOutputStream(ex.getResponseBody())) {
            for (Path path : all) {
                Path base = path.startsWith(output) ? output : logs;
                String folder = path.startsWith(output) ? "output/" : "logs/";
                String name = folder + base.relativize(path).toString().replace('\\','/');
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }
    private static class BadRequest extends Exception {
        BadRequest(String msg) { super(msg); }
    }
}
