package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Only a column with no converter or an ordered one is an {@link OrderedColumnField}, and {@code min}, {@code max} and
 * {@code countDistinct} take nothing else, so a column with any other converter does not compile there (D-93).
 */
class OrderedColumnFieldTest {

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final TableField<Entity, Entity> OTHER_ROOT = TableField.root(Entity.class);

    static final class Entity {}

    static final class Model {}

    static final class Other {}

    enum Status { NEW }

    private static class StatusConverter implements ColumnConverter<Status, String> {
        @Override
        public Status toModel(String attribute) {
            return Status.valueOf(attribute);
        }

        @Override
        public String toAttribute(Status model) {
            return model.name();
        }
    }

    private static final class OrderedStatusConverter extends StatusConverter
            implements OrderedColumnConverter<Status, String> {}

    private static final List<String> MEMBERS = List.of(
            "    static final class Entity {}",
            "    static final class Model {}",
            "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
            "    enum Status { NEW }",
            "    static final class Plain implements ColumnConverter<Status, String> {",
            "        public Status toModel(String a) { return Status.NEW; }",
            "        public String toAttribute(Status m) { return \"NEW\"; }",
            "    }",
            "    static final class Ordered implements OrderedColumnConverter<Status, String> {",
            "        public Status toModel(String a) { return Status.NEW; }",
            "        public String toAttribute(Status m) { return \"NEW\"; }",
            "    }",
            "    static final ColumnField<Model, Entity, Status> CONVERTED = ColumnField.of(Model.class, ROOT,",
            "            \"status\", Status.class, String.class, new Plain());",
            "    static final OrderedColumnField<Model, Entity, Status> ORDERED = ColumnField.of(Model.class, ROOT,",
            "            \"status\", Status.class, String.class, new Ordered());",
            "    static final OrderedColumnField<Model, Entity, String> PLAIN = ColumnField.of(Model.class, ROOT,",
            "            \"status\", String.class);");

    @Test
    void ac_agg_13_the_factories_return_an_ordered_column_for_no_converter_or_an_ordered_one() {
        ColumnField<Model, Entity, String> none = ColumnField.of(Model.class, ROOT, "status", String.class);
        OrderedColumnField<Model, Entity, Status> ordered = ColumnField.of(
                Model.class, ROOT, "status", Status.class, String.class, new OrderedStatusConverter());
        ColumnField<Model, Entity, Status> plain = ColumnField.of(
                Model.class, ROOT, "status", Status.class, String.class, new StatusConverter());

        assertThat(none).isInstanceOf(OrderedColumnField.class);
        assertThat(ordered).isInstanceOf(OrderedColumnField.class);
        assertThat(plain).isNotInstanceOf(OrderedColumnField.class);
    }

    @Test
    void ac_agg_13_an_ordered_converter_behind_a_wider_static_type_still_gives_an_ordered_column() {
        ColumnConverter<Status, String> wide = new OrderedStatusConverter();
        ColumnField<Model, Entity, Status> column =
                ColumnField.of(Model.class, ROOT, "status", Status.class, String.class, wide);

        assertThat(column).isInstanceOf(OrderedColumnField.class);
    }

    @Test
    void ac_agg_13_named_and_with_table_keep_the_kind_and_equality_ignores_it() {
        OrderedColumnField<Model, Entity, Status> ordered = ColumnField.of(
                Model.class, ROOT, "status", Status.class, String.class, new OrderedStatusConverter());
        ColumnField<Model, Entity, Status> wide = ColumnField.of(
                Model.class, ROOT, "status", Status.class, String.class, (ColumnConverter<Status, String>)
                        new OrderedStatusConverter());

        OrderedColumnField<Model, Entity, Status> named = ordered.named("state");
        OrderedColumnField<Other, Entity, Status> moved = ordered.withTable(Other.class, OTHER_ROOT);
        ColumnField<Model, Entity, Status> plainNamed = ColumnField.of(
                Model.class, ROOT, "status", Status.class, String.class, new StatusConverter()).named("state");

        assertThat(named).isEqualTo(ordered).hasSameHashCodeAs(ordered);
        assertThat(ordered).isEqualTo(wide).hasSameHashCodeAs(wide);
        assertThat(wide).isEqualTo(ordered);
        assertThat(moved.name()).isEqualTo("status");
        assertThat(plainNamed).isNotInstanceOf(OrderedColumnField.class);
    }

    @Test
    void ac_agg_13_min_max_and_count_distinct_compile_over_an_ordered_column(@TempDir Path out) throws IOException {
        assertThat(probe(out, "Agg.min(PLAIN)")).isEmpty();
        assertThat(probe(out, "Agg.max(ORDERED)")).isEmpty();
        assertThat(probe(out, "Agg.countDistinct(ORDERED)")).isEmpty();
    }

    @Test
    void ac_agg_13_min_max_and_count_distinct_over_a_column_with_another_converter_do_not_compile(@TempDir Path out)
            throws IOException {
        assertThat(probe(out, "Agg.min(CONVERTED)")).isNotEmpty();
        assertThat(probe(out, "Agg.max(CONVERTED)")).isNotEmpty();
        assertThat(probe(out, "Agg.countDistinct(CONVERTED)")).isNotEmpty();
    }

    private static List<String> probe(Path out, String call) throws IOException {
        return CompileProbe.problems(out, MEMBERS, call);
    }
}
