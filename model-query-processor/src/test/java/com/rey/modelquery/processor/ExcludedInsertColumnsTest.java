package com.rey.modelquery.processor;

import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * What {@code generateInserts} leaves out beyond R-PROC-26's first list: a database-filled {@code @Generated} column
 * and an {@code @ExcludeFromInserts} field (R-PROC-26, R-PROC-27, R-GEN-34, D-125). Hibernate is not on this module's
 * test classpath, so the annotation shapes of Hibernate 6.6 and 7.x are stubbed.
 */
class ExcludedInsertColumnsTest {

    private static final JavaFileObject EVENT_TYPE = source("org.hibernate.generator.EventType", """
            package org.hibernate.generator;

            public enum EventType { INSERT, UPDATE, DELETE }
            """);

    private static final JavaFileObject GENERATION_TIME = source("org.hibernate.annotations.GenerationTime", """
            package org.hibernate.annotations;

            public enum GenerationTime { NEVER, INSERT, UPDATE, ALWAYS }
            """);

    /** Hibernate 6.6: {@code value} (deprecated) beside {@code event}, both defaulting to {@code INSERT}. */
    private static final JavaFileObject GENERATED_6 = source("org.hibernate.annotations.Generated", """
            package org.hibernate.annotations;

            import org.hibernate.generator.EventType;

            public @interface Generated {
                GenerationTime value() default GenerationTime.INSERT;
                EventType[] event() default {EventType.INSERT};
                String sql() default "";
                boolean writable() default false;
            }
            """);

    /** Hibernate 7.x: no {@code value}. */
    private static final JavaFileObject GENERATED_7 = source("org.hibernate.annotations.Generated", """
            package org.hibernate.annotations;

            import org.hibernate.generator.EventType;

            public @interface Generated {
                EventType[] event() default {EventType.INSERT};
                String sql() default "";
                boolean writable() default false;
            }
            """);

    private static final JavaFileObject TIMESTAMPS = source("org.hibernate.annotations.CreationTimestamp", """
            package org.hibernate.annotations;

            public @interface CreationTimestamp {
                SourceType source() default SourceType.VM;
            }
            """);

    private static final JavaFileObject SOURCE_TYPE = source("org.hibernate.annotations.SourceType", """
            package org.hibernate.annotations;

            public enum SourceType { VM, DB }
            """);

    private static final JavaFileObject UPDATE_TIMESTAMP = source("org.hibernate.annotations.UpdateTimestamp", """
            package org.hibernate.annotations;

            public @interface UpdateTimestamp {
                SourceType source() default SourceType.VM;
            }
            """);

    private static final JavaFileObject CURRENT_TIMESTAMP = source("org.hibernate.annotations.CurrentTimestamp", """
            package org.hibernate.annotations;

            public @interface CurrentTimestamp {
                SourceType source() default SourceType.DB;
            }
            """);

    private static final String HIBERNATE_6_FIELDS = """
            @Generated(GenerationTime.ALWAYS) String alwaysFilled;
            @Generated(GenerationTime.UPDATE) String updateTime;
            @Generated(GenerationTime.NEVER) String neverTime;
            """;

    private static final String HIBERNATE_7_FIELDS = """
            @Generated(event = {EventType.INSERT, EventType.UPDATE}) String bothEvents;
            """;

    /** The entity for one Hibernate shape, with the {@code @Generated} variants of AC-PROC-22. */
    private static JavaFileObject entity(String shapeFields) {
        return source("ex.GadgetEntity", """
                package ex;

                import jakarta.persistence.Entity;
                import jakarta.persistence.GeneratedValue;
                import jakarta.persistence.Id;
                import org.hibernate.annotations.CreationTimestamp;
                import org.hibernate.annotations.CurrentTimestamp;
                import org.hibernate.annotations.Generated;
                import org.hibernate.annotations.GenerationTime;
                import org.hibernate.annotations.SourceType;
                import org.hibernate.annotations.UpdateTimestamp;
                import org.hibernate.generator.EventType;

                @Entity
                public class GadgetEntity {
                    @Id @GeneratedValue Long id;
                    String name;
                    @Generated String plain;
                    @Generated(event = EventType.INSERT) String insertEvent;
                    @Generated(writable = true) String writable;
                    @Generated(sql = "1") String withSql;
                    @Generated(event = EventType.UPDATE) String updateEvent;
                    @CreationTimestamp(source = SourceType.DB) java.time.Instant created;
                    @UpdateTimestamp(source = SourceType.DB) java.time.Instant updated;
                    @CurrentTimestamp java.time.Instant current;
                    @javax.annotation.processing.Generated("tool") String tooled;
                    %s
                }
                """.formatted(shapeFields));
    }

    private static JavaFileObject view(String components) {
        return source("ex.GadgetView", """
                package ex;

                import com.rey.modelquery.annotations.ExcludeFromInserts;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = GadgetEntity.class, generateInserts = true)
                public record GadgetView(@PrimaryKey Long id, %s) {}
                """.formatted(components));
    }

    /** The fields of the entity above that the 6.x shape has beyond the shared ones. */
    private static final String SHARED_COMPONENTS = "String name, String plain, String insertEvent, String writable, "
            + "String withSql, String updateEvent, java.time.Instant created, java.time.Instant updated, "
            + "java.time.Instant current, String tooled";

    private static final List<String> WRITTEN = List.of("NAME", "WRITABLE", "WITH_SQL", "UPDATE_EVENT", "CREATED",
            "UPDATED", "CURRENT", "TOOLED");

    private static final List<String> FILLED = List.of("plain", "insertEvent");

    /** The columns {@code INSERT_COLUMNS} adds, by constant name. */
    private static List<String> added(Compilation compilation) {
        Matcher matcher = Pattern.compile("\\.add(?:Key)?\\(([A-Z_]+),").matcher(
                generatedFlat(compilation, "ex.QGadgetView"));
        return matcher.results().map(result -> result.group(1)).toList();
    }

    private static void assertFilled(Compilation compilation, List<String> filled, List<String> written) {
        assertThat(errors(compilation)).isEmpty();
        assertThat(added(compilation)).containsAll(written).doesNotContainAnyElementsOf(
                filled.stream().map(ExcludedInsertColumnsTest::constant).toList());
        for (String field : filled) {
            assertThat(generated(compilation, "ex.QGadgetView"))
                    .contains("<li>{@code " + field + "}: filled by the database: {@code @Generated}</li>");
        }
    }

    private static String constant(String field) {
        return field.replaceAll("([A-Z])", "_$1").toUpperCase(Locale.ROOT);
    }

    // ---- AC-PROC-22

    @Test
    void ac_proc_22_hibernate_6_generated_shapes_are_left_out_or_written() {
        Compilation compilation = compile(EVENT_TYPE, GENERATION_TIME, GENERATED_6, TIMESTAMPS, SOURCE_TYPE,
                UPDATE_TIMESTAMP, CURRENT_TIMESTAMP, entity(HIBERNATE_6_FIELDS),
                view(SHARED_COMPONENTS + ", String alwaysFilled, String updateTime, String neverTime"));

        List<String> written = new java.util.ArrayList<>(WRITTEN);
        written.addAll(List.of("UPDATE_TIME", "NEVER_TIME"));
        assertFilled(compilation, List.of("plain", "insertEvent", "alwaysFilled"), written);
    }

    @Test
    void ac_proc_22_hibernate_7_generated_shapes_are_left_out_or_written() {
        Compilation compilation = compile(EVENT_TYPE, GENERATED_7, TIMESTAMPS, SOURCE_TYPE, UPDATE_TIMESTAMP,
                CURRENT_TIMESTAMP, GENERATION_TIME, entity(HIBERNATE_7_FIELDS),
                view(SHARED_COMPONENTS + ", String bothEvents"));

        assertFilled(compilation, List.of("plain", "insertEvent", "bothEvents"), WRITTEN);
    }

    @Test
    void ac_proc_22_a_generated_whose_type_does_not_resolve_stays_written() {
        // No Hibernate on the classpath: the entity doesn't compile, so only the diagnostics show what the processor
        // decided. The canary, an excluded generated id, proves it ran; a column it took as filled would be MQ3506.
        Compilation compilation = compile(entity(""), view("String name, String plain, "
                + "@ExcludeFromInserts String insertEvent, @ExcludeFromInserts String writable"));

        assertThat(errors(compilation)).noneMatch(message -> message.startsWith("MQ3506"));
        Compilation resolved = compile(EVENT_TYPE, GENERATION_TIME, GENERATED_6, TIMESTAMPS, SOURCE_TYPE,
                UPDATE_TIMESTAMP, CURRENT_TIMESTAMP, entity(""),
                view("String name, String plain, @ExcludeFromInserts String insertEvent, "
                        + "@ExcludeFromInserts String writable"));
        assertThat(errors(resolved)).singleElement().asString().startsWith("MQ3506: GadgetView.insertEvent: ");
    }

    // ---- AC-PROC-21, AC-GEN-29

    private static final JavaFileObject ORDER_ENTITY = source("ex.OrderEntity", """
            package ex;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class OrderEntity {
                @Id Long id;
                String customer;
                String status;
                java.time.Instant createdAt;
            }
            """);

    @Test
    void ac_proc_21_excluded_fields_leave_the_insert_members_and_keep_the_query_members() {
        Compilation compilation = compile(ORDER_ENTITY, source("ex.OrderView", """
                package ex;

                import com.rey.modelquery.annotations.ExcludeFromInserts;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderEntity.class, generateInserts = true)
                public record OrderView(
                        @PrimaryKey Long id,
                        String customer,
                        @ExcludeFromInserts String status,
                        @ExcludeFromInserts java.time.Instant createdAt) {}
                """));

        assertThat(errors(compilation)).isEmpty();
        String flat = generatedFlat(compilation, "ex.QOrderView");
        assertThat(flat).contains(".addKey(ID, OrderView::id) .add(CUSTOMER, OrderView::customer);")
                .contains("OrderedColumnField<OrderView, OrderEntity, String> STATUS")
                .contains("OrderedColumnField<OrderView, OrderEntity, Instant> CREATED_AT")
                .doesNotContain("add(STATUS").doesNotContain("add(CREATED_AT");
        // AC-GEN-29: the Javadoc names the reason.
        assertThat(generated(compilation, "ex.QOrderView"))
                .contains("<li>{@code status}: {@code @ExcludeFromInserts}</li>")
                .contains("<li>{@code createdAt}: {@code @ExcludeFromInserts}</li>");
    }

    @Test
    void ac_gen_29_the_javadoc_names_each_reason() {
        Compilation compilation = compile(EVENT_TYPE, GENERATION_TIME, GENERATED_6, TIMESTAMPS, SOURCE_TYPE,
                UPDATE_TIMESTAMP, CURRENT_TIMESTAMP, entity(""),
                view("@ExcludeFromInserts String name, String plain, String writable"));

        assertThat(errors(compilation)).isEmpty();
        assertThat(generated(compilation, "ex.QGadgetView"))
                .contains("<li>{@code name}: {@code @ExcludeFromInserts}</li>")
                .contains("<li>{@code plain}: filled by the database: {@code @Generated}</li>");
    }

    // ---- AC-DIAG-13

    private static JavaFileObject model(String kind, String annotation, String components) {
        return source("ex.Bad" + kind, """
                package ex;

                import com.rey.modelquery.annotations.*;

                %s
                public record Bad%s(%s) {}
                """.formatted(annotation, kind, components));
    }

    @Test
    void ac_diag_13_mq3506_on_a_model_without_generate_inserts() {
        String fields = "@PrimaryKey Long id, @ExcludeFromInserts String status";
        Compilation compilation = compile(ORDER_ENTITY,
                model("Query", "@QueryModel(root = OrderEntity.class)", fields),
                model("Update", "@UpdateModel(root = OrderEntity.class)", fields),
                model("Insert", "@InsertModel(root = OrderEntity.class)", fields));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                "MQ3506: BadQuery.status: @ExcludeFromInserts needs generateInserts = true on the @QueryModel",
                "MQ3506: BadUpdate.status: @ExcludeFromInserts needs generateInserts = true on the @QueryModel",
                "MQ3506: BadInsert.status: @ExcludeFromInserts needs generateInserts = true on the @QueryModel");
    }

    @Test
    void ac_diag_13_mq3506_on_a_field_generate_inserts_already_leaves_out() {
        JavaFileObject entity = source("ex.StampedEntity", """
                package ex;

                import jakarta.persistence.*;

                @Entity
                public class StampedEntity {
                    @Id @GeneratedValue Long id;
                    @Version Long version;
                    @Column(insertable = false) String closedBy;
                    String name;
                }
                """);
        Compilation compilation = compile(entity, model("Stamped",
                "@QueryModel(root = StampedEntity.class, generateInserts = true)",
                "@PrimaryKey @ExcludeFromInserts Long id, @ExcludeFromInserts Long version, "
                        + "@ExcludeFromInserts String closedBy, @Transient @ExcludeFromInserts String draft, "
                        + "String name"));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                "MQ3506: BadStamped.id: @ExcludeFromInserts does nothing; generateInserts already leaves it out "
                        + "(the id, which is generated)",
                "MQ3506: BadStamped.version: @ExcludeFromInserts does nothing; generateInserts already leaves it out "
                        + "(the @Version, which the provider writes)",
                "MQ3506: BadStamped.closedBy: @ExcludeFromInserts does nothing; generateInserts already leaves it out "
                        + "(@Column(insertable = false))",
                "MQ3506: BadStamped.draft: @ExcludeFromInserts does nothing; generateInserts already leaves it out "
                        + "(@Transient, no column)");
    }

    @Test
    void ac_diag_13_mq3501_on_an_excluded_assigned_id() {
        JavaFileObject tag = source("ex.TagEntity", """
                package ex;

                import jakarta.persistence.*;

                @Entity
                public class TagEntity {
                    @Id String code;
                    String label;
                }
                """);
        Compilation compilation = compile(tag, model("Tag",
                "@QueryModel(root = TagEntity.class, generateInserts = true)",
                "@PrimaryKey @ExcludeFromInserts String code, String label"));

        assertThat(errors(compilation)).containsExactly(
                "MQ3501: BadTag.code is @ExcludeFromInserts, but TagEntity's id 'code' is assigned, so "
                        + "generateInserts must write it");
    }

    @Test
    void ac_diag_13_mq3505_alone_when_every_column_is_excluded_or_the_model_is_grouped() {
        Compilation every = compile(ORDER_ENTITY, model("All",
                "@QueryModel(root = OrderEntity.class, generateInserts = true)",
                "@PrimaryKey @ExcludeFromInserts Long id, @ExcludeFromInserts String status"));
        Compilation grouped = compile(ORDER_ENTITY, model("Grouped",
                "@QueryModel(root = OrderEntity.class, generateInserts = true)",
                "@GroupBy @ExcludeFromInserts String status, "
                        + "@Aggregate(fn = AggregateFunction.COUNT, attribute = \"id\") Long total, "
                        + "@Transient @ExcludeFromInserts String draft"));

        assertThat(errors(every)).hasSize(1).allMatch(message -> message.startsWith("MQ3505: BadAll: "));
        assertThat(errors(grouped)).hasSize(1).allMatch(message -> message.startsWith("MQ3505: BadGrouped: "));
    }

    @Test
    void ac_diag_13_mq3021_on_a_selected_field() {
        Compilation compilation = compile(ORDER_ENTITY, model("Picked",
                "@QueryModel(root = OrderEntity.class, generateInserts = true)",
                "@PrimaryKey Long id, "
                        + "@Selected @ExcludeFromInserts com.rey.modelquery.core.SelectSet<BadPicked> selected"));

        assertThat(errors(compilation)).containsExactly(
                "MQ3021: BadPicked.selected: @Selected can't be combined with @ExcludeFromInserts");
    }
}
