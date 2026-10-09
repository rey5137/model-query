package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** A {@code Date} column reads the {@code java.sql} type a provider reports for it, and no other pair loosens (R-COL-08, D-122). */
class DateColumnTypeTest {

    static final class Entity {}

    static final class Model {}

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);

    @Test
    void ac_col_04_a_date_column_reads_each_java_sql_subtype_and_an_exact_match() {
        assertThat(ColumnField.reads(Date.class, java.sql.Date.class)).isTrue();
        assertThat(ColumnField.reads(Date.class, Time.class)).isTrue();
        assertThat(ColumnField.reads(Date.class, Timestamp.class)).isTrue();
        assertThat(ColumnField.reads(Date.class, Date.class)).isTrue();
        assertThat(ColumnField.reads(Timestamp.class, Timestamp.class)).isTrue();
        assertThat(ColumnField.reads(java.sql.Date.class, java.sql.Date.class)).isTrue();
        assertThat(ColumnField.reads(String.class, String.class)).isTrue();
    }

    @Test
    void ac_col_04_a_java_sql_column_does_not_read_a_wider_or_another_date_type() {
        assertThat(ColumnField.reads(java.sql.Date.class, Date.class)).isFalse();
        assertThat(ColumnField.reads(Timestamp.class, Date.class)).isFalse();
        assertThat(ColumnField.reads(java.sql.Date.class, Timestamp.class)).isFalse();
        assertThat(ColumnField.reads(Time.class, Timestamp.class)).isFalse();
        assertThat(ColumnField.reads(Date.class, Long.class)).isFalse();
        assertThat(ColumnField.reads(Date.class, String.class)).isFalse();
    }

    @Test
    void ac_col_04_a_type_is_named_in_full_only_when_its_simple_name_agrees_with_the_other() {
        assertThat(ColumnField.name(java.sql.Date.class, Date.class)).isEqualTo("java.sql.Date");
        assertThat(ColumnField.name(Date.class, java.sql.Date.class)).isEqualTo("java.util.Date");
        assertThat(ColumnField.name(Date.class, Timestamp.class)).isEqualTo("Date");
        assertThat(ColumnField.name(Timestamp.class, Date.class)).isEqualTo("Timestamp");
        assertThat(ColumnField.name(String.class, String.class)).isEqualTo("String");
        assertThat(ColumnField.name(Date.class, null)).isEqualTo("Date");
    }

    @Test
    void ac_col_04_a_date_column_resolves_over_a_reported_java_sql_type() {
        var column = ColumnField.of(Model.class, ROOT, "bornOn", Date.class);
        for (Class<?> reported : new Class<?>[] {java.sql.Date.class, Time.class, Timestamp.class, Date.class}) {
            assertThat(column.path(context(reported)).getJavaType()).isEqualTo(reported);
        }
    }

    @Test
    void ac_col_04_mq1001_names_both_types_in_full_for_a_java_sql_date_over_a_reported_java_util_date() {
        var column = ColumnField.of(Model.class, ROOT, "bornOn", java.sql.Date.class);
        assertThatThrownBy(() -> column.path(context(Date.class)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1001))
                .hasMessage("MQ1001: Model.bornOn: declared java.sql.Date, entity attribute Entity.bornOn is "
                        + "java.util.Date");
        var timestamp = ColumnField.of(Model.class, ROOT, "loggedAt", Timestamp.class);
        assertThatThrownBy(() -> timestamp.path(context(Date.class)))
                .hasMessage("MQ1001: Model.loggedAt: declared Timestamp, entity attribute Entity.loggedAt is Date");
    }

    /** A context over a root whose every attribute is a path of {@code reported}. */
    private static JoinContext context(Class<?> reported) {
        Path<?> path = fake(Path.class, reported, null);
        Root<?> root = fake(Root.class, Entity.class, path);
        return JoinContext.of(root, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Class<?> javaType, Object attribute) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getJavaType" -> javaType;
                    case "get" -> attribute;
                    default -> null;
                });
    }
}
