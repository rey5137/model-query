package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The staged write builders reject a wrong order at compile time (spec api/14 R-WRT-06, R-WRT-12, R-WRT-16, D-60). */
class WriteStagesCompileTest {

    @Test
    void ac_wrt_08_the_documented_stage_orders_compile(@TempDir Path out) throws IOException {
        assertThat(compile(out, "UPDATE.set(STATUS, \"PAID\").setNull(NOTE).where(f -> f.eq(STATUS, \"NEW\"))"
                + ".keepVersion().chunked(ChunkOptions.size(1_000)).build()")).isEmpty();
        assertThat(compile(out, "UPDATE.whereKey(1L).where(f -> f.eq(STATUS, \"NEW\")).expectVersion(3L)"
                + ".keepVersion().persistenceContext(PersistenceContextMode.KEEP).build()")).isEmpty();
        assertThat(compile(out, "UPDATE.whereKeys(List.of(1L, 2L)).where(f -> f.eq(STATUS, \"NEW\")).build()"))
                .isEmpty();
        assertThat(compile(out, "UPDATE.all().build()")).isEmpty();
        assertThat(compile(out, "DELETE.whereKey(1L).where(f -> f.eq(STATUS, \"NEW\")).build()")).isEmpty();
        assertThat(compile(out, "DELETE.all().chunked(ChunkOptions.defaultSize().commitEachChunk().lockKeys())"
                + ".build()")).isEmpty();
    }

    @Test
    void ac_wrt_08_where_then_all_a_second_where_and_a_second_row_choice_do_not_compile(@TempDir Path out)
            throws IOException {
        assertThat(compile(out, "UPDATE.where(f -> f.eq(STATUS, \"NEW\")).all().build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.all().where(f -> f.eq(STATUS, \"NEW\")).build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.where(f -> f).where(f -> f).build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.whereKey(1L).where(f -> f).where(f -> f).build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.whereKeys(List.of(1L)).where(f -> f).where(f -> f).build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.whereKey(1L).all().build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.whereKey(1L).whereKeys(List.of(2L)).build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.where(f -> f).set(STATUS, \"PAID\").build()")).isNotEmpty();
        assertThat(compile(out, "DELETE.where(f -> f.eq(STATUS, \"NEW\")).all().build()")).isNotEmpty();
        assertThat(compile(out, "DELETE.whereKey(1L).where(f -> f).where(f -> f).build()")).isNotEmpty();
    }

    @Test
    void ac_wrt_04_set_on_a_column_of_another_entity_does_not_compile(@TempDir Path out) throws IOException {
        assertThat(compile(out, "UPDATE.set(OTHER_NAME, \"x\").all().build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.setNull(OTHER_NAME).all().build()")).isNotEmpty();
    }

    @Test
    void ac_wrt_11_expect_version_without_where_key_does_not_compile(@TempDir Path out) throws IOException {
        assertThat(compile(out, "UPDATE.where(f -> f.eq(STATUS, \"NEW\")).expectVersion(3L).build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.whereKeys(List.of(1L)).expectVersion(3L).build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.all().expectVersion(3L).build()")).isNotEmpty();
        assertThat(compile(out, "DELETE.whereKey(1L).expectVersion(3L).build()")).isNotEmpty();
    }

    /** Compiles a write whose builder is continued by {@code call}, and returns the errors on that line. */
    private static List<String> compile(Path out, String call) throws IOException {
        String source = String.join("\n",
                "package probe;",
                "import com.rey.modelquery.core.*;",
                "import java.util.List;",
                "class Probe {",
                "    static final class Entity {}",
                "    static final class Other {}",
                "    record Model() {}",
                "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
                "    static final TableField<Entity, Other> OTHER = TableField.join(ROOT, \"other\",",
                "            jakarta.persistence.criteria.JoinType.LEFT);",
                "    static final ColumnField<Model, Entity, Long> ID = ColumnField.of(Model.class, ROOT, \"id\",",
                "            Long.class);",
                "    static final ColumnField<Model, Entity, String> STATUS = ColumnField.of(Model.class, ROOT,",
                "            \"status\", String.class);",
                "    static final ColumnField<Model, Entity, String> NOTE = ColumnField.of(Model.class, ROOT,",
                "            \"note\", String.class);",
                "    static final ColumnField<Model, Other, String> OTHER_NAME = ColumnField.of(Model.class, OTHER,",
                "            \"name\", String.class);",
                "    static final ModelUpdate.Builder<Entity, Long, Model> UPDATE =",
                "            ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID));",
                "    static final ModelDelete.Builder<Entity, Long, Model> DELETE =",
                "            ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID));",
                "    static final Object PROBE = " + call + ";",
                "}");
        long probeLine = 23;
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///probe/Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, null)) {
            List<String> options = List.of("-proc:none", "-d", out.toString(),
                    "-classpath", System.getProperty("java.class.path"));
            boolean compiled = javac.getTask(null, files, diagnostics, options, null, List.of(file)).call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    // Any error elsewhere means the probe itself is broken, not the call under test.
                    assertThat(d.getLineNumber()).as(d.toString()).isEqualTo(probeLine);
                    errors.add(d.getMessage(null));
                }
            }
            assertThat(compiled).isEqualTo(errors.isEmpty());
            return errors;
        }
    }
}
