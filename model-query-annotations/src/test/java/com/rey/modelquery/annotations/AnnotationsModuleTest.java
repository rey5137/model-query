package com.rey.modelquery.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The module's own contract: JDK only (R-PROC-01) and {@code CLASS} retention throughout (R-PROC-02). */
class AnnotationsModuleTest {

    private static final String PACKAGE = QueryModel.class.getPackageName();

    /** Every annotation type compiled into the module, found on disk so a new one cannot be missed. */
    private static List<Class<?>> annotationTypes() throws IOException, URISyntaxException, ClassNotFoundException {
        Path classes = Path.of(QueryModel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> names;
        try (Stream<Path> files = Files.list(classes.resolve(PACKAGE.replace('.', '/')))) {
            names = files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".class") && !n.equals("package-info.class"))
                    .map(n -> PACKAGE + "." + n.substring(0, n.length() - ".class".length()))
                    .toList();
        }
        List<Class<?>> types = new ArrayList<>();
        for (String name : names) {
            Class<?> type = Class.forName(name);
            if (type.isAnnotation()) {
                types.add(type);
            }
        }
        return types;
    }

    @Test
    void ac_proc_01_moduleDeclaresNoDependency() throws IOException {
        // The build compiles this module against the JDK alone as long as its POM adds nothing to the parent's
        // test-scoped dependencies; the layering test in the TCK checks the compiled classes as well.
        assertThat(Files.readString(Path.of("pom.xml"))).doesNotContain("<dependenc");
    }

    @Test
    void everyAnnotationIsClassRetained() throws Exception {
        List<Class<?>> types = annotationTypes();

        assertThat(types)
                .contains(QueryModel.class, UpdateModel.class, FilterColumns.class, Transient.class)
                .hasSize(11);
        assertThat(types).allSatisfy(type -> assertThat(type.getAnnotation(Retention.class))
                .as("@Retention of %s", type.getSimpleName())
                .isNotNull()
                .extracting(Retention::value)
                .isEqualTo(RetentionPolicy.CLASS));
    }
}
