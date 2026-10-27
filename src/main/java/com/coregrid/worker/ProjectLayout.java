package com.coregrid.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;

/** Resolves a trusted job's entry point without allowing traversal outside /job/input. */
public final class ProjectLayout {
    public record Selection(String containerScript, String containerWorkingDir, boolean repository) {}
    public static Selection select(Path input) throws IOException {
        Path info = input.resolve("entrypoint.txt");
        if (!Files.exists(info, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(input.resolve("main.py"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Missing input/main.py");
            return new Selection("/job/input/main.py", "/job/output", false);
        }
        if (!Files.isRegularFile(info, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid entrypoint manifest");
        String name = Files.readString(info, StandardCharsets.UTF_8).strip();
        if (name.length() > 160 || !name.matches("[A-Za-z0-9_./-]+\\.py") || name.startsWith("/") || name.contains("//"))
            throw new IOException("Invalid repository entry point");
        Path relative = Path.of(name).normalize();
        if (relative.toString().startsWith(".") || relative.isAbsolute() || relative.startsWith("..") ||
                name.contains("../") || name.contains("/./") || name.startsWith("./"))
            throw new IOException("Invalid repository entry path");
        Path repo = input.resolve("repo");
        Path file = repo.resolve(relative).normalize();
        if (!file.startsWith(repo) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Repository entry point missing");
        return new Selection("/job/input/repo/" + relative.toString().replace('\\','/'),
                "/job/input/repo", true);
    }
    private ProjectLayout() {}
}
