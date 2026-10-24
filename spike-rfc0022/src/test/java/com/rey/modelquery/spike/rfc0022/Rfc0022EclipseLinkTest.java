package com.rey.modelquery.spike.rfc0022;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class Rfc0022EclipseLinkTest {
    @Test
    void a4_eclipseLinkInvoiceModelInIsolatedApi32Process() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("rfc0022.eclipselink"));
        String isolated = Files.readString(Path.of("target", "link-probe", "eclipselink-5.0.2-api3.2.cp")).trim();
        List<String> entries = new ArrayList<>(List.of(isolated.split(File.pathSeparator)));
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(path -> !path.contains("jakarta.persistence-api"))
                .forEach(entries::add);
        Path log = SpikeHarness.OUT_DIR.resolve("eclipselink-5.0.2-engine.txt");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Drfc0022.output=rfc0022-eclipselink-5.0.2.tsv", "-cp", String.join(File.pathSeparator, entries),
                EclipseLinkEngineProbe.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> inherited = Map.copyOf(builder.environment());
        builder.environment().clear();
        for (String name : List.of("PATH", "JAVA_HOME", "HOME", "TMPDIR", "DOCKER_HOST", "DOCKER_TLS_VERIFY",
                "DOCKER_CERT_PATH", "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "TESTCONTAINERS_HOST_OVERRIDE")) {
            if (inherited.containsKey(name)) {
                builder.environment().put(name, inherited.get(name));
            }
        }
        Process child = builder.start();
        try {
            boolean finished = child.waitFor(Duration.ofMinutes(8).toMillis(), TimeUnit.MILLISECONDS);
            org.junit.jupiter.api.Assertions.assertTrue(finished, "EclipseLink probe timed out; see " + log);
            org.junit.jupiter.api.Assertions.assertEquals(0, child.exitValue(),
                    "EclipseLink harness failed; see " + log);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
        Files.readAllLines(log).stream().filter(line -> line.startsWith("RFC0022 ")).forEach(System.out::println);
    }
}
