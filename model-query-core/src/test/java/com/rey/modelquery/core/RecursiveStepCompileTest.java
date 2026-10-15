package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The two keys of a recursive step share one column type, checked at compile time (spec api/17 R-SRC-07). */
class RecursiveStepCompileTest {

    private static final List<String> MEMBERS = List.of(
            "    static final class Entity {}",
            "    static final class Node {}",
            "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
            "    static final ColumnField<Node, Entity, Long> ID = ColumnField.of(Node.class, ROOT, \"id\", Long.class);",
            "    static final ColumnField<Node, Entity, Long> PARENT = ColumnField.of(Node.class, ROOT, \"parent\",",
            "            Long.class);",
            "    static final ColumnField<Node, Entity, String> CODE = ColumnField.of(Node.class, ROOT, \"code\",",
            "            String.class);");

    @Test
    void r_src_07_a_step_of_two_keys_of_one_type_compiles(@TempDir Path out) throws IOException {
        assertThat(compile(out, step("step.childKey(PARENT).parentKey(ID)"))).isEmpty();
    }

    @Test
    void r_src_07_a_step_of_two_keys_of_different_types_does_not_compile(@TempDir Path out) throws IOException {
        assertThat(compile(out, step("step.childKey(PARENT).parentKey(CODE)"))).isNotEmpty();
        assertThat(compile(out, step("step.childKey(CODE).parentKey(ID)"))).isNotEmpty();
    }

    /** A step lambda, as {@code SourceQuery.Builder#recursive} takes it, since a step is only built by the builder. */
    private static String step(String body) {
        return "(java.util.function.Function<RecursiveStep<Node>, RecursiveKeys<Node, ?>>) step -> " + body;
    }

    private static List<String> compile(Path out, String call) throws IOException {
        return CompileProbe.problems(out, MEMBERS, call);
    }
}
