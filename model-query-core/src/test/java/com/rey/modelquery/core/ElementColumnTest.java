package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.JoinType;
import org.junit.jupiter.api.Test;

/**
 * {@link ColumnField#element}: the value of an element collection, read on the collection's join itself with no
 * attribute (D-249). Its rendering runs in the TCK ({@code ElementFilterTest}).
 */
class ElementColumnTest {

    static class Order {}

    static final class SubOrder extends Order {}

    static final class Model {}

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

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, String> TAGS = TableField.join(ROOT, "tags", JoinType.LEFT);

    @Test
    void ac_exe_30_an_element_column_is_named_by_its_collection_and_reads_the_join_path() {
        OrderedColumnField<Model, String, String> tag = ColumnField.element(Model.class, TAGS, String.class);

        assertThat(tag.name()).isEqualTo("tags");
        assertThat(tag.path()).isEqualTo("tags");
        assertThat(tag.identityPath()).isEqualTo("tags");
        assertThat(tag.table()).isSameAs(TAGS);
        assertThat(tag.type()).isEqualTo(String.class);
        assertThat(tag).hasToString("Model.tags");
        // Equal to another element column of the same join, never to a column of an attribute named like it.
        assertThat(tag).isEqualTo(ColumnField.element(Model.class, TAGS, String.class))
                .isNotEqualTo(ColumnField.of(Model.class, TAGS, "tags", String.class));
    }

    @Test
    void ac_exe_30_a_converted_element_column_is_ordered_only_with_an_ordered_converter() {
        ColumnField<Model, String, Status> plain =
                ColumnField.element(Model.class, TAGS, Status.class, String.class, new StatusConverter());
        OrderedColumnField<Model, String, Status> ordered =
                ColumnField.element(Model.class, TAGS, Status.class, String.class, new OrderedStatusConverter());

        assertThat(plain).isNotInstanceOf(OrderedColumnField.class);
        assertThat(plain.attributeType()).isEqualTo(String.class);
        assertThat(ordered.type()).isEqualTo(Status.class);
        assertThat(ordered.path()).isEqualTo("tags");
    }

    @Test
    void ac_exe_30_an_element_column_needs_a_join() {
        assertThatThrownBy(() -> ColumnField.element(Model.class, ROOT, String.class))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("ColumnField.element(...) reads the value of a join along an element "
                        + "collection")
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1002);
        assertThatThrownBy(() -> ColumnField.element(Model.class, ROOT.treat(SubOrder.class), String.class))
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1002);
        assertThatThrownBy(() -> ColumnField.element(Model.class, TableField.source(Model.class), String.class))
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1905);
    }

    @Test
    void ac_exe_30_of_refuses_an_empty_attribute() {
        assertThatThrownBy(() -> ColumnField.of(Model.class, TAGS, "", String.class))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("ColumnField.element(...)")
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1002);
        assertThatThrownBy(() -> ColumnField.of(Model.class, TAGS, "", Status.class, String.class,
                new StatusConverter()))
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1002);
        assertThatThrownBy(() -> ColumnField.of(Model.class, TAGS, "", Status.class, String.class,
                new OrderedStatusConverter()))
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1002);
    }
}
