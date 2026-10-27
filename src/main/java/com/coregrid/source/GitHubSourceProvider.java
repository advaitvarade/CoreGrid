package com.coregrid.source;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Public GitHub sources only. Clone happens on the coordinator, never in a job container. */
public final class GitHubSourceProvider {
    private static final long MAX_REPO_BYTES = 25L * 1024 * 1024;
    private static final int MAX_FILES = 1000;
    public record Result(String canonicalUrl, String commit, String entryPoint, String ref) {}
    public static class InvalidSource extends Exception {
        public InvalidSource(String message) { super(message); }
    }

    public static String canonicalUrl(String provided) throws InvalidSource {
        if (provided == null || provided.length() > 250) throw new InvalidSource("Invalid GitHub URL length");
        try {
            URI u = URI.create(provided);
            if (!"https".equals(u.getScheme()) || !"github.com".equals(u.getHost()) ||
                    u.getPort() != -1 || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null)
                throw new InvalidSource("Only https://github.com/owner/repo is allowed");
            String path = u.getPath();
            if (path == null || !path.matches("/[A-Za-z0-9][A-Za-z0-9_.-]{0,38}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}(?:\\.git)?/?"))
                throw new InvalidSource("Expected a public GitHub owner/repository URL");
            String[] segments = path.split("/");
            String owner = segments[1], repo = segments[2];
            if (repo.endsWith(".git")) repo = repo.substring(0, repo.length()-4);
            if (owner.equals(".") || owner.equals("..") || repo.equals(".") || repo.equals(".."))
                throw new InvalidSource("Invalid repository path");
            return "https://github.com/" + owner + "/" + repo + ".git";
        } catch (IllegalArgumentException e) {
            throw new InvalidSource("Invalid GitHub repository URL");
        }
    }

    public static String entryPoint(String path) throws InvalidSource {
        if (path == null || path.length() > 160 || !path.matches("[A-Za-z0-9_./-]+\\.py"))
            throw new InvalidSource("Entry point must be a relative .py file");
        Path p = Path.of(path).normalize();
        if (p.isAbsolute() || p.startsWith("..") || p.getNameCount() > 8 ||
                p.toString().startsWith(".") || path.contains("//"))
            throw new InvalidSource("Unsafe or too deeply nested entry point");
        for (Path section : Path.of(path)) if (section.toString().equals("..") || section.toString().equals("."))
            throw new InvalidSource("Entry point cannot contain . or ..");
        return p.toString().replace('\\', '/');
    }

    public static String branch(String ref) throws InvalidSource {
        if (ref == null || ref.isBlank()) return "";
        if (ref.length() > 80 || !ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") ||
                ref.contains("..") || ref.contains("//") || ref.endsWith("/") || ref.endsWith(".lock"))
            throw new InvalidSource("Invalid branch or tag name");
        return ref;
    }

    public Result prepare(String url, String reference, String mainFile, Path inputDir) throws Exception {
        String gitUrl = canonicalUrl(url);
        String branch = branch(reference);
        String entry = entryPoint(mainFile);
        Path repo = inputDir.resolve("repo");
        if (Files.exists(repo)) throw new InvalidSource("Job already contains a repository");
        Files.createDirectories(inputDir);
        List<String> command = new ArrayList<>(List.of("git", "-c", "protocol.file.allow=never",
                "-c", "protocol.ext.allow=never", "-c", "core.hooksPath=/dev/null",
                "clone", "--depth=1", "--single-branch", "--no-tags", "--no-recurse-submodules"));
        if (!branch.isEmpty()) { command.add("--branch"); command.add(branch); }
        command.add(gitUrl); command.add(repo.toString());
        run(command, inputDir.resolve("clone.log"), Duration.ofSeconds(50));
        validateTree(repo);
        Path selected = repo.resolve(entry).normalize();
        if (!selected.startsWith(repo) || !Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS))
            throw new InvalidSource("Entry point " + entry + " does not exist in repository");
        Path log = inputDir.resolve("rev-parse.log");
        String sha = capture(List.of("git", "-C", repo.toString(), "rev-parse", "HEAD"), log);
        if (!sha.matches("[a-f0-9]{40,64}")) throw new InvalidSource("Unable to identify commit");
        deleteTree(repo.resolve(".git"));
        Files.writeString(inputDir.resolve("entrypoint.txt"), entry, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Files.deleteIfExists(inputDir.resolve("clone.log"));
        Files.deleteIfExists(log);
        return new Result(gitUrl, sha, entry, branch);
    }

    private static void validateTree(Path root) throws Exception {
        long total = 0; int count = 0;
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>)stream::iterator) {
                if (Files.isSymbolicLink(p)) throw new InvalidSource("Symlinks are not supported in repositories");
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                count++;
                total += Files.size(p);
                if (count > MAX_FILES || total > MAX_REPO_BYTES)
                    throw new InvalidSource("Repository exceeds 1000 files or 25 MB");
            }
        }
    }
    private static String capture(List<String> command, Path log) throws Exception {
        run(command, log, Duration.ofSeconds(10));
        return Files.readString(log, StandardCharsets.UTF_8).trim();
    }
    private static void run(List<String> command, Path log, Duration timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String,String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = pb.start();
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor();
            throw new InvalidSource("Git operation timed out");
        }
        if (process.exitValue() != 0) throw new InvalidSource("Git operation failed (check public URL, ref and network access)");
    }
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
