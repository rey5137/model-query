package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The staged write builders reject a wrong order at compile time (spec api/14 R-WRT-06, R-WRT-12, R-WRT-16, R-WRT-20,
 * D-60, D-68).
 */
class WriteStagesCompileTest {

    private static final List<String> MEMBERS = List.of(
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
            "            ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID));");

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

    @Test
    void ac_wrt_15_start_after_takes_the_key_type_after_where_or_all_in_any_option_order(@TempDir Path out)
            throws IOException {
        assertThat(compile(out, "UPDATE.set(STATUS, \"PAID\").where(f -> f.eq(STATUS, \"NEW\")).keepVersion()"
                + ".chunked(ChunkOptions.size(10).commitEachChunk(), 42L)"
                + ".persistenceContext(PersistenceContextMode.KEEP).build()")).isEmpty();
        assertThat(compile(out, "DELETE.all().persistenceContext(PersistenceContextMode.KEEP)"
                + ".chunked(ChunkOptions.defaultSize(), 42L).build()")).isEmpty();
        assertThat(compile(out, "UPDATE.all().chunked(ChunkOptions.defaultSize(), \"42\").build()")).isNotEmpty();
        assertThat(compile(out, "UPDATE.whereKeys(List.of(1L)).chunked(ChunkOptions.defaultSize(), 1L).build()"))
                .isNotEmpty();
        assertThat(compile(out, "UPDATE.whereKey(1L).chunked(ChunkOptions.defaultSize(), 1L).build()")).isNotEmpty();
        assertThat(compile(out, "DELETE.whereKeys(List.of(1L)).chunked(ChunkOptions.defaultSize(), 1L).build()"))
                .isNotEmpty();
    }

    /** Compiles a write whose builder is continued by {@code call}, and returns the errors on that line. */
    private static List<String> compile(Path out, String call) throws IOException {
        return CompileProbe.problems(out, MEMBERS, call);
    }
}
