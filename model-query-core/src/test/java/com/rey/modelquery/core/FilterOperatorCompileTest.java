package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code op(...)} rejects a wrong call at compile time: a value of the wrong type, a field of another model, and an
 * operator over a column of another type (spec api/12 R-FLT-20, P-2).
 */
class FilterOperatorCompileTest {

    private static final List<String> MEMBERS = List.of(
            "    static final class Entity {}",
            "    static final class Model {}",
            "    static final class Other {}",
            "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
            "    static final ColumnField<Model, Entity, String> NAME = ColumnField.of(Model.class, ROOT,",
            "            \"name\", String.class);",
            "    static final ColumnField<Model, Entity, String> CODE = ColumnField.of(Model.class, ROOT,",
            "            \"code\", String.class);",
            "    static final ColumnField<Other, Entity, String> OTHER_NAME = ColumnField.of(Other.class, ROOT,",
            "            \"name\", String.class);",
            "    static final ColumnField<Model, Entity, Integer> SIZE = ColumnField.of(Model.class, ROOT, \"size\",",
            "            Integer.class);",
            "    static final FilterOperator<String, String> MATCHES = FilterOperator.of(\"matches\",",
            "            (ctx, left, right) -> ctx.cb().equal(left, right));");
    private static final String WHERE = "(java.util.function.UnaryOperator<Filters<Model>>) f -> f.";

    @Test
    void ac_flt_27_an_operator_with_its_own_type_compiles_in_each_form(@TempDir Path out) throws IOException {
        assertThat(probe(out, WHERE + "op(MATCHES, NAME, \"a\")")).isEmpty();
        assertThat(probe(out, WHERE + "op(MATCHES, NAME, java.util.Optional.of(\"a\"))")).isEmpty();
        assertThat(probe(out, WHERE + "op(MATCHES, NAME, CODE)")).isEmpty();
    }

    @Test
    void ac_flt_27_a_value_of_the_wrong_type_does_not_compile(@TempDir Path out) throws IOException {
        assertThat(probe(out, WHERE + "op(MATCHES, NAME, 1)")).isNotEmpty();
        assertThat(probe(out, WHERE + "op(MATCHES, NAME, java.util.Optional.of(1))")).isNotEmpty();
        assertThat(probe(out, WHERE + "op(MATCHES, NAME, SIZE)")).isNotEmpty();
    }

    @Test
    void ac_flt_27_a_field_of_another_model_does_not_compile(@TempDir Path out) throws IOException {
        assertThat(probe(out, WHERE + "op(MATCHES, OTHER_NAME, \"a\")")).isNotEmpty();
        assertThat(probe(out, WHERE + "op(MATCHES, NAME, OTHER_NAME)")).isNotEmpty();
    }

    @Test
    void ac_flt_27_a_string_operator_on_an_integer_column_does_not_compile(@TempDir Path out) throws IOException {
        assertThat(probe(out, WHERE + "op(MATCHES, SIZE, \"a\")")).isNotEmpty();
        assertThat(probe(out, WHERE + "op(MATCHES, SIZE, java.util.Optional.of(\"a\"))")).isNotEmpty();
    }

    private static List<String> probe(Path out, String call) throws IOException {
        return CompileProbe.problems(out, MEMBERS, call, "-Xlint:unchecked");
    }
}
