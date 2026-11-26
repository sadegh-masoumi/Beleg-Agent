package dev.belegagent.loadtest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load test in two phases, run as two invocations by {@code scripts/loadtest.sh}:
 *
 * <pre>
 * upload   --docs N --tenant T             upload N unique PNGs through the API (workers scaled to 0)
 * measure  --tenant T --label L [...]      wait until the tenant's queue is drained, write results
 * </pre>
 *
 * Uploading first and starting the workers afterwards measures the drain rate of a backlog (month-end batch)
 * independently of the upload rate. Timings come from PostgreSQL: the completion time of a document is the
 * {@code created_at} of its processing run.
 *
 * <p>Options: {@code --api URL} (default http://api:8080), {@code --db URL --db-user U --db-password P}
 * (default from DB_URL, DB_USER, DB_PASSWORD), {@code --upload-concurrency N} (16), {@code --out DIR}
 * (docs/loadtest), {@code --timeout-s N} (1800), and descriptive {@code --workers --concurrency --rpm --failure-rate
 * --latency} that are only recorded in the results.
 */
public final class LoadTest {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private LoadTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("usage: upload|measure [options]");
        }
        Map<String, String> a = parse(args);
        switch (args[0]) {
            case "upload" -> upload(a);
            case "measure" -> measure(a);
            default -> throw new IllegalArgumentException("Unknown mode " + args[0]);
        }
    }

    // ---------------------------------------------------------------- upload

    private static void upload(Map<String, String> a) throws Exception {
        String api = a.getOrDefault("api", "http://api:8080");
        String tenant = required(a, "tenant");
        int docs = Integer.parseInt(required(a, "docs"));
        int concurrency = Integer.parseInt(a.getOrDefault("upload-concurrency", "16"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Semaphore permits = new Semaphore(concurrency);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < docs; i++) {
                permits.acquire();
                int n = i;
                executor.execute(() -> {
                    try {
                        HttpResponse<String> response = http.send(multipart(api, tenant, n), HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 201) {
                            ok.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                            System.err.println("upload " + n + ": HTTP " + response.statusCode() + " " + response.body());
                        }
                    } catch (IOException | InterruptedException e) {
                        failed.incrementAndGet();
                        System.err.println("upload " + n + ": " + e);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf(Locale.ROOT, "uploaded %d documents (%d failed) in %.1f s: %.1f uploads/s at concurrency %d%n",
                ok.get(), failed.get(), seconds, ok.get() / seconds, concurrency);
        Path out = Path.of(a.getOrDefault("out", "docs/loadtest"));
        Files.createDirectories(out);
        Files.writeString(out.resolve(".upload-" + tenant), String.format(Locale.ROOT, "%.2f", ok.get() / seconds));
        if (failed.get() > 0) {
            System.exit(1);
        }
    }

    /** A unique tiny "PNG": the API checks magic bytes only, the simulated model does not decode images. */
    private static HttpRequest multipart(String api, String tenant, int n) {
        String boundary = "----beleg" + UUID.randomUUID();
        byte[] png = new byte[PNG_SIGNATURE.length + 64];
        System.arraycopy(PNG_SIGNATURE, 0, png, 0, PNG_SIGNATURE.length);
        byte[] random = new byte[64];
        ThreadLocalRandom.current().nextBytes(random);
        System.arraycopy(random, 0, png, PNG_SIGNATURE.length, random.length);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"lt-" + n
                + ".png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(png);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(api + "/api/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Tenant-Id", tenant)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    // ---------------------------------------------------------------- measure

    private static void measure(Map<String, String> a) throws Exception {
        String tenant = required(a, "tenant");
        String label = required(a, "label");
        long timeoutS = Long.parseLong(a.getOrDefault("timeout-s", "1800"));
        Path out = Path.of(a.getOrDefault("out", "docs/loadtest"));
        try (Connection db = DriverManager.getConnection(
                a.getOrDefault("db", System.getenv("DB_URL")),
                a.getOrDefault("db-user", System.getenv("DB_USER")),
                a.getOrDefault("db-password", System.getenv("DB_PASSWORD")))) {

            long deadline = System.nanoTime() + timeoutS * 1_000_000_000L;
            Map<String, Long> counts;
            while (true) {
                counts = counts(db, tenant);
                long open = counts.getOrDefault("RECEIVED", 0L) + counts.getOrDefault("PROCESSING", 0L);
                System.out.println("[" + label + "] " + counts);
                if (open == 0) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Timeout: " + open + " documents still open");
                }
                Thread.sleep(2000);
            }

            Stats s = stats(db, tenant);
            List<long[]> timeline = timeline(db, tenant);
            double uploadRate = readUploadRate(out, tenant);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("workers", a.getOrDefault("workers", ""));
            row.put("concurrency_per_worker", a.getOrDefault("concurrency", ""));
            row.put("model_latency", a.getOrDefault("latency", ""));
            row.put("rpm_limit", a.getOrDefault("rpm", "0"));
            row.put("failure_rate", a.getOrDefault("failure-rate", "0"));
            row.put("docs", String.valueOf(s.docs()));
            row.put("drain_s", fmt(s.drainSeconds()));
            row.put("throughput_docs_per_s", fmt(s.throughput()));
            row.put("peak_docs_per_s", String.valueOf(timeline.stream().mapToLong(t -> t[1]).max().orElse(0)));
            row.put("upload_docs_per_s", fmt(uploadRate));
            row.put("model_calls", String.valueOf(s.modelCalls()));
            row.put("retried_docs", String.valueOf(s.retriedDocs()));
            row.put("accepted", String.valueOf(counts.getOrDefault("ACCEPTED", 0L)));
            row.put("needs_review", String.valueOf(counts.getOrDefault("NEEDS_REVIEW", 0L)));
            row.put("dead_letter", String.valueOf(counts.getOrDefault("DEAD_LETTER", 0L)));
            row.put("failed", String.valueOf(counts.getOrDefault("FAILED", 0L)));
            appendCsv(out.resolve("results.csv"), row);
            writeTimeline(out.resolve("timeline-" + label + ".csv"), timeline);
            System.out.println("[" + label + "] " + row);
        }
    }

    private record Stats(long docs, double drainSeconds, double throughput, long modelCalls, long retriedDocs) {
    }

    private static Map<String, Long> counts(Connection db, String tenant) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT status, count(*) FROM document WHERE tenant_id = ? GROUP BY status ORDER BY status")) {
            ps.setString(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return counts;
    }

    /** Drain rate between the first and the last completed run (the backlog exists before the workers start). */
    private static Stats stats(Connection db, String tenant) throws SQLException {
        String sql = """
                SELECT count(DISTINCT r.document_id),
                       extract(epoch FROM max(r.created_at) - min(r.created_at)),
                       (SELECT count(*) FROM extraction e JOIN document d2 ON d2.id = e.document_id
                         WHERE d2.tenant_id = ? AND e.source <> 'E_INVOICE'),
                       (SELECT count(*) FROM document d3 WHERE d3.tenant_id = ? AND d3.attempts > 1)
                  FROM processing_run r JOIN document d ON d.id = r.document_id
                 WHERE d.tenant_id = ?
                """;
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, tenant);
            ps.setString(2, tenant);
            ps.setString(3, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long docs = rs.getLong(1);
                double seconds = rs.getDouble(2);
                return new Stats(docs, seconds, seconds > 0 ? (docs - 1) / seconds : 0, rs.getLong(3), rs.getLong(4));
            }
        }
    }

    /** Completed documents per second since the first completion. */
    private static List<long[]> timeline(Connection db, String tenant) throws SQLException {
        String sql = """
                WITH runs AS (SELECT r.created_at FROM processing_run r JOIN document d ON d.id = r.document_id
                               WHERE d.tenant_id = ?)
                SELECT floor(extract(epoch FROM created_at - (SELECT min(created_at) FROM runs)))::int AS s, count(*)
                  FROM runs GROUP BY 1 ORDER BY 1
                """;
        List<long[]> rows = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new long[]{rs.getLong(1), rs.getLong(2)});
                }
            }
        }
        return rows;
    }

    private static double readUploadRate(Path out, String tenant) throws IOException {
        Path file = out.resolve(".upload-" + tenant);
        if (!Files.exists(file)) {
            return 0;
        }
        double rate = Double.parseDouble(Files.readString(file).strip());
        Files.delete(file);
        return rate;
    }

    private static void appendCsv(Path file, Map<String, String> row) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.writeString(file, String.join(",", row.keySet()) + "\n");
        }
        Files.writeString(file, String.join(",", row.values()) + "\n", StandardOpenOption.APPEND);
    }

    private static void writeTimeline(Path file, List<long[]> timeline) {
        StringBuilder sb = new StringBuilder("second,completed\n");
        timeline.forEach(t -> sb.append(t[0]).append(',').append(t[1]).append('\n'));
        try {
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String required(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return v;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --key value at " + args[i]);
            }
            m.put(args[i].substring(2), args[++i]);
        }
        return m;
    }
}
