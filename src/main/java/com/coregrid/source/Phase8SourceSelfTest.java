package com.coregrid.source;

import com.coregrid.worker.ProjectLayout;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class Phase8SourceSelfTest {
    public static void main(String[] args) throws Exception {
        var source = GitHubSourceProvider.canonicalUrl("https://github.com/example/demo");
        if (!source.equals("https://github.com/example/demo.git")) throw new AssertionError(source);
        for (String url : new String[]{"http://github.com/a/b", "https://github.com.evil.test/a/b",
                "https://github.com/a/b?x=1", "file:///tmp/repo"}) {
            try { GitHubSourceProvider.canonicalUrl(url); throw new AssertionError("Accepted " + url); }
            catch (GitHubSourceProvider.InvalidSource expected) {}
        }
        for (String name : new String[]{"../main.py", "/etc/passwd.py", "a/../main.py", "main.py;touch-bad.py"}) {
            try { GitHubSourceProvider.entryPoint(name); throw new AssertionError("Accepted " + name); }
            catch (GitHubSourceProvider.InvalidSource expected) {}
        }
        Path dir = Files.createTempDirectory("coregrid-layout-");
        try {
            Files.writeString(dir.resolve("main.py"), "print('single')", StandardCharsets.UTF_8);
            var single = ProjectLayout.select(dir);
            if (!single.containerScript().equals("/job/input/main.py")) throw new AssertionError();
            Files.createDirectories(dir.resolve("repo/src"));
            Files.writeString(dir.resolve("repo/src/train.py"), "print('repo')", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("entrypoint.txt"), "src/train.py", StandardCharsets.UTF_8);
            var multi = ProjectLayout.select(dir);
            if (!multi.repository() || !multi.containerScript().equals("/job/input/repo/src/train.py") ||
                    !multi.containerWorkingDir().equals("/job/input/repo")) throw new AssertionError(multi);
            System.out.println("PASS: safe GitHub URL and entry-point validation");
            System.out.println("PASS: single-file and repository layout resolution");
        } finally { GitHubSourceProvider.deleteTree(dir); }
    }
}
