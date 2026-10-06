package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The staged insert builders reject a wrong order at compile time (spec api/14 R-WRT-27, R-WRT-34, D-60, D-117). */
class InsertStagesCompileTest {

    private static final List<String> MEMBERS = List.of(
            "    static final class Entity {}",
            "    static final class Source {}",
            "    static final class Other {}",
            "    record Row(Long id, String status) {}",
            "    record View() {}",
            "    record OtherView() {}",
            "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
            "    static final TableField<Source, Source> SOURCE = TableField.root(Source.class);",
            "    static final TableField<Source, Other> SOURCE_OTHER = TableField.join(SOURCE, \"other\",",
            "            jakarta.persistence.criteria.JoinType.LEFT);",
            "    static final ColumnField<Row, Entity, Long> ID = ColumnField.of(Row.class, ROOT, \"id\", Long.class);",
            "    static final ColumnField<Row, Entity, String> STATUS = ColumnField.of(Row.class, ROOT, \"status\",",
            "            String.class);",
            "    static final ColumnField<Entity, Entity, String> NOTE = ColumnField.of(Entity.class, ROOT, \"note\",",
            "            String.class);",
            "    static final ColumnField<View, Source, Long> V_ID = ColumnField.of(View.class, SOURCE, \"id\",",
            "            Long.class);",
            "    static final ColumnField<View, Source, String> V_STATUS = ColumnField.of(View.class, SOURCE,",
            "            \"status\", String.class);",
            "    static final ColumnField<OtherView, Source, String> O_STATUS = ColumnField.of(OtherView.class,",
            "            SOURCE, \"status\", String.class);",
            "    static final InsertColumns<Row, Entity> COLUMNS = InsertColumns.<Row, Entity>of(ROOT)",
            "            .addKey(ID, Row::id).add(STATUS, Row::status);",
            "    static final List<Row> ROWS = List.of(new Row(1L, \"NEW\"));",
            "    static final ModelInsert.SelectStart<Entity, Row> SELECT = ModelInsert.select(COLUMNS, SOURCE);",
            "    static final ValuesInsert.Rows<Entity, Long, Row> VALUES =",
            "            ValuesInsert.builder(COLUMNS, Long.class, ROWS);");

    @Test
    void ac_wrt_33_the_documented_insert_stage_orders_compile(@TempDir Path out) throws IOException {
        assertThat(compile(out, "SELECT.map(ID, V_ID).set(NOTE, \"x\").map(STATUS, V_STATUS)"
                + ".where(f -> f.eq(V_STATUS, \"NEW\")).chunked(ChunkOptions.size(10))"
                + ".persistenceContext(PersistenceContextMode.KEEP).build()")).isEmpty();
        assertThat(compile(out, "VALUES.set(NOTE, \"x\").chunked(ChunkOptions.size(10))"
                + ".persistenceContext(PersistenceContextMode.KEEP).build()")).isEmpty();
        assertThat(compile(out, "VALUES.onConflict(STATUS).doNothing().anyUniqueKey()"
                + ".chunked(ChunkOptions.size(10)).build()")).isEmpty();
        assertThat(compile(out, "VALUES.onConflict(STATUS, ID).doUpdate(u -> u.setFromRow(STATUS).set(NOTE, \"x\")"
                + ".setNull(NOTE).where(f -> f.eq(STATUS, \"NEW\"))).chunked(ChunkOptions.size(10)).keepVersion()"
                + ".anyUniqueKey().build()")).isEmpty();
        assertThat(compile(out, "ModelPersist.of(COLUMNS, Long.class, new Row(1L, \"NEW\"))")).isEmpty();
    }

    @Test
    void ac_wrt_33_a_source_column_of_another_model_or_a_join_source_does_not_compile(@TempDir Path out)
            throws IOException {
        assertThat(compile(out, "SELECT.map(ID, V_ID).map(STATUS, O_STATUS).all().build()")).isNotEmpty();
        assertThat(compile(out, "SELECT.map(STATUS, V_ID).all().build()")).isNotEmpty();
        assertThat(compile(out, "ModelInsert.select(COLUMNS, SOURCE_OTHER)")).isNotEmpty();
    }

    @Test
    void ac_wrt_33_an_insert_select_needs_a_map_then_one_row_choice_and_takes_no_conflict(@TempDir Path out)
            throws IOException {
        assertThat(compile(out, "SELECT.all().build()")).isNotEmpty();
        assertThat(compile(out, "SELECT.set(NOTE, \"x\").map(ID, V_ID).all().build()")).isNotEmpty();
        assertThat(compile(out, "SELECT.map(ID, V_ID).map(STATUS, V_STATUS).build()")).isNotEmpty();
        assertThat(compile(out, "SELECT.map(ID, V_ID).all().where(f -> f).build()")).isNotEmpty();
        assertThat(compile(out, "SELECT.map(ID, V_ID).all().map(STATUS, V_STATUS).build()")).isNotEmpty();
        assertThat(compile(out, "SELECT.map(ID, V_ID).onConflict(ID).doNothing().all().build()")).isNotEmpty();
        assertThat(compile(out, "SELECT.map(ID, V_ID).all().keepVersion().build()")).isNotEmpty();
        assertThat(compile(out, "SELECT.map(ID, V_ID).all().chunked(ChunkOptions.size(1), 1L).build()"))
                .isNotEmpty();
    }

    @Test
    void ac_wrt_33_conflict_stages_need_an_action_and_come_before_the_options(@TempDir Path out) throws IOException {
        assertThat(compile(out, "VALUES.onConflict(STATUS).build()")).isNotEmpty();
        assertThat(compile(out, "VALUES.onConflict().doNothing().build()")).isNotEmpty();
        assertThat(compile(out, "VALUES.chunked(ChunkOptions.size(10)).onConflict(STATUS).doNothing().build()"))
                .isNotEmpty();
        assertThat(compile(out, "VALUES.onConflict(STATUS).doNothing().keepVersion().build()")).isNotEmpty();
        assertThat(compile(out, "VALUES.onConflict(STATUS).doUpdate(u -> u).build()")).isNotEmpty();
        assertThat(compile(out, "VALUES.onConflict(STATUS).doUpdate(u -> u.setFromRow(STATUS).where(f -> f)"
                + ".set(NOTE, \"x\")).build()")).isNotEmpty();
        assertThat(compile(out, "VALUES.onConflict(NOTE).doNothing().build()")).isNotEmpty();
        assertThat(compile(out, "VALUES.setNull(NOTE).build()")).isNotEmpty();
    }

    @Test
    void ac_wrt_33_a_conflict_insert_is_not_a_values_insert(@TempDir Path out) throws IOException {
        assertThat(compile(out, "(java.util.function.Supplier<ValuesInsert<Entity, Long, Row>>) () -> "
                + "VALUES.onConflict(STATUS).doNothing().build()")).isNotEmpty();
        assertThat(compile(out, "(java.util.function.Supplier<ModelInsert<Entity, Row>>) () -> VALUES.build()"))
                .isEmpty();
    }

    /** Compiles an insert built by {@code call}, and returns the errors on that line. */
    private static List<String> compile(Path out, String call) throws IOException {
        return CompileProbe.problems(out, MEMBERS, call);
    }
}
