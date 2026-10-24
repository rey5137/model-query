package com.rey.modelquery.spike.rfc0022;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.SessionFactory;

/** JDBC replay isolates execution cost; complete result equality is checked before timing. */
final class CostProbes {
    private static boolean initialized;
    private CostProbes() {}

    static void run(SpikeHarness h, SessionFactory sessions) throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("rfc0022.plans", "true"))) {
            h.notRun("B.2", "timings-and-plans", "iteration: -Drfc0022.plans=false");
            return;
        }
        Path timings = SpikeHarness.OUT_DIR.resolve("rfc0022-timings-" + SpikeHarness.HIB + ".tsv");
        if (!initialized) {
            Files.writeString(timings, "vendor\tcell\tvariant\tstatus\tmedian_ms\trows\tvalue\n");
            initialized = true;
        }
        Path plans = SpikeHarness.OUT_DIR.resolve("plans").resolve(SpikeHarness.HIB);
        Files.createDirectories(plans);
        Files.writeString(plans.resolve(h.vendor().toLowerCase() + ".txt"), "");
        try (Connection connection = h.raw().getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(60);
                statement.execute(switch (h.vendor()) {
                    case "POSTGRESQL" -> "analyze";
                    case "MYSQL" -> "analyze table invoice, employee";
                    default -> "analyze";
                });
            }
            for (Q.Kind kind : List.of(Q.Kind.CONTENT, Q.Kind.COUNT, Q.Kind.STREAM)) {
                h.cell("B.2", kind.toString(), () -> {
                    var options = Q.Options.defaults();
                    var actual = Q.run(sessions, options, kind);
                    var rendered = h.lastStatement();
                    SpikeHarness.expect(rendered.sql().toLowerCase().contains("left join employee"),
                            "rendered statement lost its LEFT employee join");
                    String nativeSql = Q.nativeInvoice(options, kind);
                    Q.equal(actual, Q.nativeRows(h, nativeSql));
                    var nativeStatement = new SpikeHarness.Stmt(nativeSql, "", List.of());
                    List<SpikeHarness.Stmt> variants = List.of(rendered, nativeStatement);
                    List<List<Double>> samples = List.of(new ArrayList<>(), new ArrayList<>());
                    for (int round = 0; round < 9; round++) {
                        for (int offset = 0; offset < 2; offset++) {
                            int variant = (round + offset) % 2;
                            long start = System.nanoTime();
                            long rows = execute(connection, variants.get(variant));
                            double elapsed = (System.nanoTime() - start) / 1e6;
                            SpikeHarness.expect(rows == actual.size(), "timed replay row count differs");
                            if (round >= 2) {
                                samples.get(variant).add(elapsed);
                            }
                        }
                    }
                    for (int variant = 0; variant < 2; variant++) {
                        String name = variant == 0 ? "renderer" : "native";
                        Collections.sort(samples.get(variant));
                        double median = samples.get(variant).get(3);
                        String plan = explain(connection, h.vendor(), variants.get(variant));
                        Files.writeString(plans.resolve(h.vendor().toLowerCase() + ".txt"),
                                "## " + kind + " / " + name + "\nSQL: " + variants.get(variant) + "\n"
                                        + plan + "\n\n", StandardOpenOption.APPEND);
                        String value = kind == Q.Kind.COUNT && h.vendor().equals("POSTGRESQL")
                                ? "info only: employee PK LEFT join removed="
                                        + !plan.toLowerCase().contains("employee")
                                : "info only: execution cost, no performance acceptance limit";
                        Files.writeString(timings, String.join("\t", h.vendor(), kind.toString(), name, "PASS",
                                Double.toString(median), Integer.toString(actual.size()), value) + "\n",
                                StandardOpenOption.APPEND);
                    }
                    return "7 alternating measured runs + 2 warmups; equal results; timings are info only";
                });
            }
        }
    }

    private static long execute(Connection connection, SpikeHarness.Stmt statement) throws Exception {
        try (var prepared = SpikeHarness.prepare(connection, "", statement)) {
            prepared.setQueryTimeout(60);
            try (ResultSet result = prepared.executeQuery()) {
                long rows = 0;
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    for (int column = 1; column <= columns; column++) {
                        result.getObject(column);
                    }
                    rows++;
                }
                return rows;
            }
        }
    }

    private static String explain(Connection connection, String vendor, SpikeHarness.Stmt statement) throws Exception {
        String prefix = switch (vendor) {
            case "POSTGRESQL" -> "explain (analyze, buffers) ";
            case "MYSQL" -> "explain analyze ";
            default -> "explain ";
        };
        try (var prepared = SpikeHarness.prepare(connection, prefix, statement)) {
            prepared.setQueryTimeout(60);
            try (ResultSet result = prepared.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (result.next()) {
                    lines.add(result.getString(1));
                }
                return String.join("\n", lines);
            }
        }
    }
}
