package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** The built-in ordered converters the processor takes for a {@code Timestamp} attribute (R-PROC-07, D-84). */
class BuiltInConverterTest {

    private static final JavaFileObject EVENT_ENTITY = source("shop.EventEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.sql.Timestamp;

            @Entity
            public class EventEntity {
                @Id
                Long id;
                String region;
                Timestamp createdAt;
                Timestamp updatedAt;
            }
            """);

    private static final JavaFileObject LATER = source("shop.Later", """
            package shop;

            import com.rey.modelquery.core.ColumnConverter;
            import java.sql.Timestamp;
            import java.util.Date;

            public class Later implements ColumnConverter<Date, Timestamp> {
                public static final Later INSTANCE = new Later();

                public Date toModel(Timestamp attribute) {
                    return new Date(attribute.getTime() + 1);
                }

                public Timestamp toAttribute(Date model) {
                    return new Timestamp(model.getTime() - 1);
                }
            }
            """);

    private static final String IMPORTS = """
            package shop;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.time.Instant;
            import java.time.LocalDateTime;
            import java.util.Date;

            """;

    private static Compilation compileModel(String name, String declaration) {
        return compile(EVENT_ENTITY, LATER, source("shop." + name, IMPORTS + declaration));
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }

    @Test
    void ac_proc_11_an_instant_or_date_field_over_a_timestamp_takes_the_built_in_converter() {
        Compilation compilation = compileModel("EventView", """
                @QueryModel(root = EventEntity.class)
                public record EventView(
                        @PrimaryKey Long id,
                        @Column(attribute = "createdAt") Instant created,
                        @Column(attribute = "updatedAt") Date updated) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QEventView"))
                .contains("import com.rey.modelquery.core.DateTimestampConverter;")
                .contains("import com.rey.modelquery.core.InstantTimestampConverter;")
                .contains("ColumnField<EventView, EventEntity, Instant> CREATED = ColumnField.of(EventView.class, "
                        + "ROOT, \"createdAt\", Instant.class, Timestamp.class, InstantTimestampConverter.INSTANCE) "
                        + ".named(\"created\");")
                .contains("ColumnField<EventView, EventEntity, Date> UPDATED = ColumnField.of(EventView.class, "
                        + "ROOT, \"updatedAt\", Date.class, Timestamp.class, DateTimestampConverter.INSTANCE) "
                        + ".named(\"updated\");");
    }

    @Test
    void ac_proc_11_a_named_converter_wins_over_the_built_in() {
        Compilation compilation = compileModel("EventView", """
                @QueryModel(root = EventEntity.class)
                public record EventView(
                        @PrimaryKey Long id,
                        @Column(attribute = "createdAt", converter = Later.class) Date created) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QEventView"))
                .contains("ColumnField<EventView, EventEntity, Date> CREATED = ColumnField.of(EventView.class, "
                        + "ROOT, \"createdAt\", Date.class, Timestamp.class, Later.INSTANCE).named(\"created\");")
                .doesNotContain("DateTimestampConverter");
    }

    @Test
    void ac_proc_11_a_type_no_built_in_converter_bridges_is_still_mq3002() {
        Compilation compilation = compileModel("EventView", """
                @QueryModel(root = EventEntity.class)
                public record EventView(
                        @PrimaryKey Long id,
                        @Column(attribute = "createdAt") LocalDateTime created,
                        @Column(attribute = "region") Instant region) {}
                """);

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3002,
                        "EventView.created: model type LocalDateTime, entity attribute type Timestamp"),
                message(DiagnosticCode.MQ3002, "EventView.region: model type Instant, entity attribute type String"));
    }

    @Test
    void ac_proc_11_a_timestamp_field_stays_plain_and_a_date_subclass_is_still_mq3002() {
        Compilation plain = compileModel("EventView", """
                @QueryModel(root = EventEntity.class)
                public record EventView(
                        @PrimaryKey Long id,
                        @Column(attribute = "createdAt") java.sql.Timestamp created) {}
                """);
        Compilation subclass = compileModel("EventView", """
                @QueryModel(root = EventEntity.class)
                public record EventView(
                        @PrimaryKey Long id,
                        @Column(attribute = "createdAt") java.sql.Date created) {}
                """);

        assertThat(plain).succeededWithoutWarnings();
        assertThat(generatedFlat(plain, "shop.QEventView"))
                .contains("ColumnField<EventView, EventEntity, Timestamp> CREATED = ColumnField.of(EventView.class, "
                        + "ROOT, \"createdAt\", Timestamp.class).named(\"created\");")
                .doesNotContain("TimestampConverter");
        assertThat(errors(subclass)).containsExactly(message(DiagnosticCode.MQ3002,
                "EventView.created: model type Date, entity attribute type Timestamp"));
    }

    @Test
    void ac_proc_12_min_and_max_over_a_timestamp_read_as_an_instant_or_a_date_field() {
        Compilation compilation = compileModel("EventSummary", """
                @QueryModel(root = EventEntity.class)
                public record EventSummary(
                        @GroupBy String region,
                        @Aggregate(fn = AggregateFunction.MIN, attribute = "createdAt") Instant first,
                        @Aggregate(fn = AggregateFunction.MAX, attribute = "createdAt") Date last,
                        @Aggregate(fn = AggregateFunction.COUNT, attribute = "createdAt", distinct = true)
                                Long instants) {}
                """);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QEventSummary"))
                .contains("AggregateField<EventSummary, Instant> FIRST = Agg.min( ColumnField.of("
                        + "EventSummary.class, ROOT, \"createdAt\", Instant.class, Timestamp.class, "
                        + "InstantTimestampConverter.INSTANCE));")
                .contains("AggregateField<EventSummary, Date> LAST = Agg.max( ColumnField.of("
                        + "EventSummary.class, ROOT, \"createdAt\", Date.class, Timestamp.class, "
                        + "DateTimestampConverter.INSTANCE));")
                .contains("AggregateField<EventSummary, Long> INSTANTS = Agg.countDistinct( ColumnField.of("
                        + "EventSummary.class, ROOT, \"createdAt\", Timestamp.class));");
    }

    @Test
    void ac_proc_12_sum_avg_and_an_unbridged_min_over_a_timestamp_keep_mq3202() {
        Compilation compilation = compileModel("EventSummary", """
                @QueryModel(root = EventEntity.class)
                public record EventSummary(
                        @GroupBy String region,
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "createdAt") Date total,
                        @Aggregate(fn = AggregateFunction.AVG, attribute = "createdAt") Instant mean,
                        @Aggregate(fn = AggregateFunction.MIN, attribute = "createdAt") LocalDateTime first) {}
                """);

        assertThat(errors(compilation)).containsExactly(
                message(DiagnosticCode.MQ3202, "EventSummary.total: SUM over Timestamp is not supported; it sums "
                        + "BigDecimal, Double, Long, Integer, Short and Byte"),
                message(DiagnosticCode.MQ3202,
                        "EventSummary.mean: AVG over Timestamp is not supported; it averages a numeric attribute"),
                message(DiagnosticCode.MQ3202,
                        "EventSummary.first: MIN over Timestamp returns Timestamp, field is LocalDateTime"));
    }
}
