package com.rey.modelquery.spike.rfc0019.benchmarks;

import com.rey.modelquery.spike.rfc0019.SpikeHarness;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

public class Rfc0019TimingsTest {

    private static final Path OUT_DIR = Path.of("..", "rfc", "0019-spike-data");

    @BeforeAll
    static void init() throws Exception {
        Files.createDirectories(OUT_DIR);
        BenchmarkDatabase.startContainers();
    }

    @AfterAll
    static void cleanup() {
        BenchmarkDatabase.stopContainers();
    }

    @Test
    void runTimings() throws Exception {
        boolean smoke = Boolean.getBoolean("spike.quick.smoke");
        String customInclude = System.getProperty("spike.include");
        int forks = Integer.getInteger("spike.forks", smoke ? 1 : 2);
        String suffix = System.getProperty("spike.out.suffix", "");
        boolean enableJfr = Boolean.getBoolean("spike.jfr");

        Path tsvFile = OUT_DIR.resolve("performance-" + SpikeHarness.HIB + suffix + ".tsv");
        Path tsvGzFile = OUT_DIR.resolve("performance-" + SpikeHarness.HIB + suffix + ".tsv.gz");
        Path jsonFile = OUT_DIR.resolve("performance-" + SpikeHarness.HIB + suffix + ".json");

        ChainedOptionsBuilder opt = new OptionsBuilder();

        if (smoke) {
            opt.include("^.*PartABenchmark\\.today_RA1_executor$")
                    .warmupIterations(1)
                    .warmupTime(TimeValue.seconds(1))
                    .measurementIterations(1)
                    .measurementTime(TimeValue.seconds(1))
                    .forks(1);
        } else if (customInclude != null && !customInclude.isBlank()) {
            opt.include(customInclude).forks(forks);
        } else {
            opt.include(PartABenchmark.class.getSimpleName())
                    .include(GatingCellBenchmark.class.getSimpleName())
                    .include(MySqlInformationalBenchmark.class.getSimpleName())
                    .include(PartBBenchmark.class.getSimpleName())
                    .forks(forks);
        }

        if (!SpikeHarness.HIB.startsWith("7.")) {
            opt.exclude(".*RA4.*");
        }

        opt.jvmArgs(
                "-Dspike.pg.url=" + BenchmarkDatabase.pgJdbcUrl(),
                "-Dspike.pg.user=" + BenchmarkDatabase.pgUsername(),
                "-Dspike.pg.password=" + BenchmarkDatabase.pgPassword(),
                "-Dspike.mysql.url=" + BenchmarkDatabase.mysqlJdbcUrl(),
                "-Dspike.mysql.user=" + BenchmarkDatabase.mysqlUsername(),
                "-Dspike.mysql.password=" + BenchmarkDatabase.mysqlPassword(),
                "-Duser.timezone=UTC"
        )
        .addProfiler(GCProfiler.class)
        .resultFormat(ResultFormatType.JSON)
        .result(jsonFile.toAbsolutePath().toString())
        .output("target/jmh.log");

        if (enableJfr) {
            Path jfrDir = Path.of("target", "jfr");
            Files.createDirectories(jfrDir);
            opt.addProfiler(JavaFlightRecorderProfiler.class, "dir=" + jfrDir.toAbsolutePath());
        }

        Options built = opt.build();
        Collection<RunResult> results = new Runner(built).run();

        // A benchmark that throws produces no RunResult; list every selected method so it gets a FAIL row.
        Map<String, RunResult> byMethod = new LinkedHashMap<>();
        for (Class<?> c : List.of(PartABenchmark.class, GatingCellBenchmark.class,
                MySqlInformationalBenchmark.class, PartBBenchmark.class)) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Benchmark.class)) continue;
                String full = c.getName() + "." + m.getName();
                boolean in = built.getIncludes().stream().anyMatch(r -> Pattern.compile(r).matcher(full).find());
                boolean out = built.getExcludes().stream().anyMatch(r -> Pattern.compile(r).matcher(full).find());
                if (in && !out) byMethod.put(m.getName(), null);
            }
        }
        for (RunResult rr : results) {
            String fullBenchmark = rr.getParams().getBenchmark();
            byMethod.put(fullBenchmark.substring(fullBenchmark.lastIndexOf('.') + 1), rr);
        }

        List<String> lines = new ArrayList<>();
        lines.add("vendor\thibernate\tpart\tquery\troute\tfork\titeration\tscore_ms\talloc_rate_norm\tstatus");

        for (Map.Entry<String, RunResult> e : byMethod.entrySet()) {
            String method = e.getKey();
            RunResult rr = e.getValue();

            String vendor = "POSTGRESQL";
            String part = "A";
            String query = "today";
            String route = "RA.1";

            if (method.startsWith("mysql_")) {
                vendor = "MYSQL";
                part = "A";
                query = "today";
                if (method.contains("RA0")) route = "RA.0";
                else if (method.contains("RA1")) route = "RA.1";
                else if (method.contains("RA3")) route = "RA.3_copy";
                else if (method.contains("RA5")) route = "RA.5";
            } else if (method.startsWith("gating_")) {
                vendor = "POSTGRESQL";
                part = "gating";
                query = "gating";
                route = method.contains("buildCache") ? "gating_cache" : "gating_fresh";
            } else if (method.startsWith("rb")) {
                vendor = "POSTGRESQL";
                part = "B";
                query = "flat86k";
                if (method.startsWith("rb0")) route = "RB.0";
                else if (method.startsWith("rb1")) route = "RB.1";
                else if (method.startsWith("rb2")) route = "RB.2";
                else if (method.startsWith("rb3")) route = "RB.3";
                else if (method.startsWith("rb4")) route = "RB.4";
                else if (method.startsWith("rb5")) route = "RB.5";
                else if (method.startsWith("rb6")) route = "RB.6";
                else if (method.startsWith("rb7_stream")) route = "RB.7_stream";
                else if (method.startsWith("rb7_export")) route = "RB.7_export";
            } else {
                vendor = "POSTGRESQL";
                part = "A";
                if (method.startsWith("today_")) query = "today";
                else if (method.startsWith("one_")) query = "one";
                else if (method.startsWith("page_")) query = "page";
                else if (method.startsWith("grouped_")) query = "grouped";
                else if (method.startsWith("orderedBy_")) query = "orderedBy";

                if (method.contains("RA0")) route = "RA.0";
                else if (method.contains("RA1")) route = "RA.1";
                else if (method.contains("RA2")) route = "RA.2";
                else if (method.contains("RA3_copyTree")) route = "RA.3_copy";
                else if (method.contains("RA3_noCopyTree")) route = "RA.3_nocopy";
                else if (method.contains("RA4b")) route = "RA.4b";
                else if (method.contains("RA4_")) route = "RA.4";
                else if (method.contains("RA5")) route = "RA.5";
                else if (method.contains("RA6")) route = "RA.6";
            }

            if (rr == null) {
                lines.add(String.format(Locale.ROOT, "%s\t%s\t%s\t%s\t%s\t0\t0\tNaN\t0.0\tFAIL",
                        vendor, SpikeHarness.HIB, part, query, route));
                continue;
            }
            int fork = 1;
            for (BenchmarkResult br : rr.getBenchmarkResults()) {
                int iteration = 1;
                for (IterationResult ir : br.getIterationResults()) {
                    if (ir.getParams().getType() != IterationType.MEASUREMENT) {
                        continue;
                    }
                    double rawScore = ir.getPrimaryResult() != null ? ir.getPrimaryResult().getScore() : Double.NaN;
                    String unit = ir.getPrimaryResult() != null ? ir.getPrimaryResult().getScoreUnit() : "";

                    double scoreMs = rawScore;
                    if (!Double.isNaN(rawScore)) {
                        if (unit.contains("us") || unit.toLowerCase(Locale.ROOT).contains("micro")) {
                            scoreMs = rawScore / 1000.0;
                        } else if (unit.contains("ns") || unit.toLowerCase(Locale.ROOT).contains("nano")) {
                            scoreMs = rawScore / 1_000_000.0;
                        } else if (unit.contains("s") && !unit.contains("ms")) {
                            scoreMs = rawScore * 1000.0;
                        }
                    }

                    Result allocResult = ir.getSecondaryResults().get("·gc.alloc.rate.norm");
                    if (allocResult == null) {
                        allocResult = ir.getSecondaryResults().get("gc.alloc.rate.norm");
                    }
                    double allocNorm = allocResult != null ? allocResult.getScore() : 0.0;
                    String status = Double.isNaN(scoreMs) ? "FAIL" : "PASS";

                    lines.add(String.format(Locale.ROOT, "%s\t%s\t%s\t%s\t%s\t%d\t%d\t%.6f\t%.1f\t%s",
                            vendor, SpikeHarness.HIB, part, query, route, fork, iteration, scoreMs, allocNorm, status));
                    iteration++;
                }
                fork++;
            }
        }

        String content = String.join("\n", lines) + "\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > 200_000) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(bytes);
                gzos.finish();
                Files.write(tsvGzFile, baos.toByteArray());
            }
            Files.deleteIfExists(tsvFile);
        } else {
            Files.write(tsvFile, bytes);
            Files.deleteIfExists(tsvGzFile);
        }
    }
}
