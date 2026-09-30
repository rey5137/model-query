package com.rey.modelquery.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** Inspects the packaged, shaded processor jar; runs after {@code package}, with the jar's path passed in. */
class ProcessorJarIT {

    /** Set by the build to the jar the {@code package} phase produced. */
    private static final String JAR_PROPERTY = "processorJar";

    private static final String JAVAPOET = "com/squareup/";
    private static final String SHADED_JAVAPOET = "com/rey/modelquery/processor/shaded/javapoet/";

    @Test
    void ac_gen_09_processor_jar_contains_javapoet_only_under_the_shaded_package() throws IOException {
        List<String> entries;
        try (var jar = new JarFile(System.getProperty(JAR_PROPERTY))) {
            entries = jar.stream().map(JarEntry::getName).toList();
        }

        assertThat(entries).contains("com/rey/modelquery/processor/ModelQueryProcessor.class");
        assertThat(entries).noneMatch(entry -> entry.startsWith(JAVAPOET));
        assertThat(entries).anyMatch(entry -> entry.startsWith(SHADED_JAVAPOET) && entry.endsWith(".class"));
        assertThat(entries).contains(
                "META-INF/services/javax.annotation.processing.Processor",
                "META-INF/gradle/incremental.annotation.processors");
    }
}
