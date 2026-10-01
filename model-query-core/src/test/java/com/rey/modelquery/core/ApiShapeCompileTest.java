package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The 1.0 signatures reject a wrong call at compile time: an {@code or} with fewer than two branches, a setter bound
 * to another model's column, and a {@code PageSpec} or {@code ExportOptions} built by constructor (D-87, D-88).
 */
class ApiShapeCompileTest {

    private static final List<String> MEMBERS = List.of(
            "    static final class Entity {}",
            "    static final class Model { String status; }",
            "    static final class Other {}",
            "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
            "    static final ColumnField<Model, Entity, String> STATUS = ColumnField.of(Model.class, ROOT,",
            "            \"status\", String.class);",
            "    static final ColumnField<Other, Entity, String> OTHER_STATUS = ColumnField.of(Other.class, ROOT,",
            "            \"status\", String.class);");
    private static final String WHERE = "(java.util.function.UnaryOperator<Filters<Model>>) f -> f.";

    @Test
    void ac_flt_03_or_takes_two_or_three_branches_or_a_list_with_no_unchecked_warning(@TempDir Path out)
            throws IOException {
        assertThat(probe(out, WHERE + "or(a -> a.eq(STATUS, \"A\"), b -> b.eq(STATUS, \"B\"))")).isEmpty();
        assertThat(probe(out, WHERE + "or(a -> a, b -> b, c -> c.eq(STATUS, \"C\"))")).isEmpty();
        assertThat(probe(out, WHERE + "or(List.of(a -> a.eq(STATUS, \"A\")))")).isEmpty();
        assertThat(probe(out, WHERE + "or(List.of())")).isEmpty();
    }

    @Test
    void ac_flt_03_an_or_with_fewer_than_two_branches_does_not_compile(@TempDir Path out) throws IOException {
        assertThat(probe(out, WHERE + "or(a -> a.eq(STATUS, \"A\"))")).isNotEmpty();
        assertThat(probe(out, WHERE + "or()")).isNotEmpty();
    }

    @Test
    void r_col_11_a_setter_binds_only_a_column_of_the_mappers_own_model(@TempDir Path out) throws IOException {
        assertThat(probe(out, "RowMapper.setters(Model::new).bind(STATUS, (m, v) -> m.status = v)")).isEmpty();
        assertThat(probe(out, "RowMapper.setters(Model::new).bind(OTHER_STATUS, (m, v) -> m.status = v)"))
                .isNotEmpty();
    }

    @Test
    void ac_exe_05_page_spec_and_export_options_have_no_public_constructor(@TempDir Path out) throws IOException {
        assertThat(probe(out, "PageSpec.of(2, 20)")).isEmpty();
        assertThat(probe(out, "new PageSpec(2, 20)")).isNotEmpty();
        assertThat(probe(out, "new ExportOptions(java.util.OptionalInt.empty(), Limit.unlimited())")).isNotEmpty();
    }

    private static List<String> probe(Path out, String call) throws IOException {
        return CompileProbe.problems(out, MEMBERS, call, "-Xlint:unchecked");
    }
}
