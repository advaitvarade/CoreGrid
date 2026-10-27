package com.coregrid.api;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** SQLite persistence. Each operation owns a JDBC connection; updates are serialized. */
public final class JobRepository {
    public record JobRow(String id, String userId, String name, String status, int cpu,
                         boolean gpu, String environment, String argsJson, int memoryMb,
                         int timeoutSeconds, String createdAt, String updatedAt,
                         Integer exitCode, String detail) {}

    private final String url;
    public JobRepository(Path dbPath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        this.url = "jdbc:sqlite:" + dbPath.toAbsolutePath().normalize();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("""
                CREATE TABLE IF NOT EXISTS jobs (
                  id TEXT PRIMARY KEY, user_id TEXT NOT NULL, name TEXT NOT NULL,
                  status TEXT NOT NULL, cpu INTEGER NOT NULL, gpu INTEGER NOT NULL,
                  environment TEXT NOT NULL, args_json TEXT NOT NULL,
                  memory_mb INTEGER NOT NULL, timeout_seconds INTEGER NOT NULL,
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                  exit_code INTEGER, detail TEXT NOT NULL DEFAULT ''
                )
            """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_jobs_user ON jobs(user_id,created_at DESC)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS job_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL,
                    event TEXT NOT NULL, created_at TEXT NOT NULL
                )
            """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS job_sources (
                    job_id TEXT PRIMARY KEY, repository_url TEXT NOT NULL, ref TEXT NOT NULL,
                    commit_sha TEXT NOT NULL, entry_point TEXT NOT NULL
                )
            """);
            // After coordinator restart, previous ephemeral workers cannot be trusted.
            s.executeUpdate("""
                UPDATE jobs SET status='INTERRUPTED', detail='Coordinator restarted',
                    updated_at=CURRENT_TIMESTAMP
                WHERE status IN ('QUEUED','DISPATCHED','RUNNING','CANCELING')
            """);
        }
    }
    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    public synchronized void create(JobRow job) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("""
            INSERT INTO jobs(id,user_id,name,status,cpu,gpu,environment,args_json,
                memory_mb,timeout_seconds,created_at,updated_at,detail)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
        """)) {
            p.setString(1, job.id()); p.setString(2, job.userId());
            p.setString(3, job.name()); p.setString(4, job.status());
            p.setInt(5, job.cpu()); p.setInt(6, job.gpu() ? 1 : 0);
            p.setString(7, job.environment()); p.setString(8, job.argsJson());
            p.setInt(9, job.memoryMb()); p.setInt(10, job.timeoutSeconds());
            p.setString(11, job.createdAt()); p.setString(12, job.updatedAt());
            p.setString(13, job.detail()); p.executeUpdate();
        }
    }
    public synchronized void recordRepositorySource(String jobId, String url, String ref,
                                                     String commit, String entry) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(
                "INSERT INTO job_sources(job_id,repository_url,ref,commit_sha,entry_point) VALUES(?,?,?,?,?)")) {
            p.setString(1, jobId); p.setString(2, url); p.setString(3, ref);
            p.setString(4, commit); p.setString(5, entry); p.executeUpdate();
        }
    }
    public synchronized java.util.Map<String, String> repositorySource(String jobId) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(
                "SELECT * FROM job_sources WHERE job_id=?")) {
            p.setString(1, jobId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return java.util.Map.of("type", "single-file");
                return java.util.Map.of("type", "github", "repositoryUrl", r.getString("repository_url"),
                        "ref", r.getString("ref"), "commit", r.getString("commit_sha"),
                        "entryPoint", r.getString("entry_point"));
            }
        }
    }
    public synchronized JobRow get(String id) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("SELECT * FROM jobs WHERE id=?")) {
            p.setString(1, id);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }
    public synchronized List<JobRow> list(String userId) throws SQLException {
        List<JobRow> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(
                "SELECT * FROM jobs WHERE user_id=? ORDER BY created_at DESC LIMIT 100")) {
            p.setString(1, userId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) rows.add(map(rs));
            }
        }
        return rows;
    }
    public synchronized boolean transition(String id, String status, Integer exitCode, String detail,
                                            String... allowed) throws SQLException {
        if (allowed.length == 0) throw new IllegalArgumentException("Allowed states required");
        String placeholders = String.join(",", java.util.Collections.nCopies(allowed.length, "?"));
        String sql = "UPDATE jobs SET status=?, updated_at=?, exit_code=?, detail=? WHERE id=? AND status IN (" + placeholders + ")";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, status); p.setString(2, Instant.now().toString());
            if (exitCode == null) p.setNull(3, Types.INTEGER); else p.setInt(3, exitCode);
            p.setString(4, detail == null ? "" : detail.substring(0, Math.min(detail.length(), 1000)));
            p.setString(5, id);
            for (int i = 0; i < allowed.length; i++) p.setString(i + 6, allowed[i]);
            int updated = p.executeUpdate();
            if (updated > 0) {
                try (PreparedStatement event = c.prepareStatement("INSERT INTO job_events(job_id,event,created_at) VALUES(?,?,?)")) {
                    event.setString(1, id); event.setString(2, status); event.setString(3, Instant.now().toString());
                    event.executeUpdate();
                }
            }
            return updated > 0;
        }
    }
    public synchronized void interruptRunning(String reason) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("""
             UPDATE jobs SET status='INTERRUPTED', detail=?, updated_at=?
             WHERE status IN ('DISPATCHED','RUNNING','CANCELING')
        """)) {
            p.setString(1, reason); p.setString(2, Instant.now().toString()); p.executeUpdate();
        }
    }
    private static JobRow map(ResultSet rs) throws SQLException {
        int exit = rs.getInt("exit_code");
        Integer exitCode = rs.wasNull() ? null : exit;
        return new JobRow(rs.getString("id"),rs.getString("user_id"),rs.getString("name"),
                rs.getString("status"),rs.getInt("cpu"),rs.getInt("gpu") != 0,
                rs.getString("environment"),rs.getString("args_json"),rs.getInt("memory_mb"),
                rs.getInt("timeout_seconds"),rs.getString("created_at"),rs.getString("updated_at"),
                exitCode,rs.getString("detail"));
    }
}
