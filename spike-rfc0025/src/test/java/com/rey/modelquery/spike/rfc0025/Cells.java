package com.rey.modelquery.spike.rfc0025;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The spike's cells, one TSV row each in {@code rfc/0025-spike-data/rfc0025-<hibernate version>.tsv}: vendor, item,
 * case, status ({@code PASS} or {@code FAIL}), value. The file is rewritten by each run.
 */
final class Cells {
    private final Path file;
    private int failures;

    Cells() {
        Path dir = Path.of("").toAbsolutePath().getParent().resolve("rfc/0025-spike-data");
        file = dir.resolve("rfc0025-" + org.hibernate.Version.getVersionString() + ".tsv");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, "vendor\titem\tcase\tstatus\tvalue\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records a cell, {@code PASS} when {@code ok}. */
    void record(String vendor, String item, String name, boolean ok, String value) {
        if (!ok) {
            failures++;
        }
        String row = String.join("\t", vendor, item, name, ok ? "PASS" : "FAIL",
                value.replace('\t', ' ').replace('\n', ' ')) + "\n";
        try {
            Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int failures() {
        return failures;
    }
}
