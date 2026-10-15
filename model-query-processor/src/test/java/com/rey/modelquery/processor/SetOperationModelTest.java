package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * A branch of a set operation: {@code @SourceModel(shape)}, {@code @Discriminator} and {@code processor/30}
 * R-PROC-38, {@code processor/32} MQ3034, {@code processor/31} R-GEN-37.
 */
class SetOperationModelTest {

    private static final JavaFileObject ORDER_ENTITY = source("shop.OrderEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.EnumType;
            import jakarta.persistence.Enumerated;
            import jakarta.persistence.Id;
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;
            import java.math.BigDecimal;
            import java.time.Instant;
            import java.util.Date;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                String title;
                Instant at;
                @Enumerated(EnumType.STRING)
                Status status;
                @Temporal(TemporalType.TIMESTAMP)
                Date day;
                BigDecimal amount;
            }
            """);

    /** The branch's own entity: the same columns under other names, and other mappings for two of them. */
    private static final JavaFileObject REFUND_ENTITY = source("shop.RefundEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.EnumType;
            import jakarta.persistence.Enumerated;
            import jakarta.persistence.Id;
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;
            import java.time.Instant;
            import java.util.Date;

            @Entity
            public class RefundEntity {
                @Id
                Long id;
                String kind;
                String reason;
                Instant refundedAt;
                @Enumerated(EnumType.ORDINAL)
                Status status;
                @Temporal(TemporalType.DATE)
                Date day;
                Long amount;
            }
            """);

    private static final JavaFileObject STATUS = source("shop.Status", """
            package shop;

            public enum Status {
                OPEN,
                DONE;
            }
            """);

    /** A converter of a branch column the shape stores raw (R-PROC-38). */
    private static final JavaFileObject REASON_CONVERTER = source("shop.ReasonConverter", """
            package shop;

            import com.rey.modelquery.core.ColumnConverter;

            public final class ReasonConverter implements ColumnConverter<String, String> {
                public static final ReasonConverter INSTANCE = new ReasonConverter();

                @Override
                public String toModel(String attribute) {
                    return attribute;
                }

                @Override
                public String toAttribute(String model) {
                    return model;
                }
            }
            """);

    /** The shape's fields, and the branch's: the same ones, read from another entity. */
    private static final String SHAPE_FIELDS =
            "@Discriminator(\"ORDER\") @PrimaryKey String kind, @PrimaryKey Long id, String title, Instant at";

    private static final String BRANCH_FIELDS =
            "@Discriminator(\"REFUND\") @PrimaryKey String kind, @PrimaryKey Long id, "
                    + "@Column(attribute = \"reason\") String title, @Column(attribute = \"refundedAt\") Instant at";

    /** The shape: an ordinary source over its own root, and the first branch of its own operations. */
    private static final JavaFileObject FEED_ITEM = feedItem("FeedItem", SHAPE_FIELDS);

    /** The branch of {@link #FEED_ITEM}: the same fields, read from another entity. */
    private static final JavaFileObject REFUND_FEED_ITEM = branch("RefundFeedItem", "FeedItem", BRANCH_FIELDS);

    private static JavaFileObject feedItem(String name, String fields) {
        return source("shop." + name, """
                package shop;

                import com.rey.modelquery.annotations.Discriminator;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import com.rey.modelquery.annotations.Window;
                import com.rey.modelquery.annotations.WindowFunction;
                import java.math.BigDecimal;
                import java.time.Instant;
                import java.util.Date;

                @SourceModel(root = OrderEntity.class)
                public record %s(%s) {}
                """.formatted(name, fields));
    }

    /** A branch named {@code name} of the shape {@code shape}, over {@link #REFUND_ENTITY}. */
    private static JavaFileObject branch(String name, String shape, String fields) {
        return source("shop." + name, """
                package shop;

                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.Discriminator;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import com.rey.modelquery.annotations.Window;
                import com.rey.modelquery.annotations.WindowFunction;
                import jakarta.persistence.Enumerated;
                import jakarta.persistence.EnumType;
                import jakarta.persistence.Temporal;
                import jakarta.persistence.TemporalType;
                import java.time.Instant;
                import java.util.Date;

                @SourceModel(root = RefundEntity.class, shape = %s.class)
                public record %s(%s) {}
                """.formatted(shape, name, fields));
    }

    private static Compilation compileFeed() {
        return compile(ORDER_ENTITY, REFUND_ENTITY, STATUS, REASON_CONVERTER, FEED_ITEM, REFUND_FEED_ITEM);
    }

    /** {@link #compileFeed()} with the branch's fields written as {@code fields}. */
    private static Compilation compileBranch(String fields) {
        return compile(ORDER_ENTITY, REFUND_ENTITY, STATUS, REASON_CONVERTER, FEED_ITEM,
                branch("RefundFeedItem", "FeedItem", fields));
    }

    /** The pair with the shape's fields written as {@code shapeFields} and the branch's as {@code branchFields}. */
    private static Compilation compilePair(String shapeFields, String branchFields) {
        return compile(ORDER_ENTITY, REFUND_ENTITY, STATUS, REASON_CONVERTER, feedItem("FeedItem", shapeFields),
                branch("RefundFeedItem", "FeedItem", branchFields));
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }

    @Test
    void ac_proc_34_a_discriminated_shape_matches_its_golden_file() {
        Compilation compilation = compileFeed();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QFeedItem")).isEqualTo(resource("golden/QFeedItem.java"));
    }

    @Test
    void ac_proc_34_a_branch_matches_its_golden_file() {
        Compilation compilation = compileFeed();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QRefundFeedItem"))
                .isEqualTo(resource("golden/QRefundFeedItem.java"));
    }

    @Test
    void ac_proc_34_a_branch_source_is_a_source_query_of_its_shape() {
        Compilation compilation = compileFeed();

        assertThat(compilation).succeededWithoutWarnings();
        // R-PROC-38: the branch's source() builds a SourceQuery<Shape>, so it combines with its shape's branches.
        assertThat(generatedFlat(compilation, "shop.QRefundFeedItem")).contains(
                "public static SourceQuery.Builder<RefundEntity, RefundFeedItem, FeedItem> source()",
                "return SourceQuery.builder(RefundFeedItem.class, ROOT, ALL, KEY)"
                        + ".shape(FeedItem.class).discriminator(KIND, \"REFUND\");");
    }

    @Test
    void ac_proc_34_a_shape_source_fixes_its_own_discriminator() {
        Compilation compilation = compileFeed();

        assertThat(compilation).succeededWithoutWarnings();
        // R-SRC-06: the shape is the first branch of its own operations, so it fixes its discriminator too.
        assertThat(generatedFlat(compilation, "shop.QFeedItem")).contains(
                "public static SourceQuery.Builder<OrderEntity, FeedItem, FeedItem> source()",
                "return SourceQuery.builder(FeedItem.class, ROOT, ALL, KEY).discriminator(KIND, \"ORDER\");",
                "PrimaryKey.composite(KIND, ID)");
    }

    @Test
    void ac_proc_34_a_field_of_another_name_is_mq3034() {
        Compilation compilation = compileBranch("""
                @Discriminator("REFUND") @PrimaryKey String kind, @PrimaryKey Long id,
                @Column(attribute = "reason") String label, @Column(attribute = "refundedAt") Instant at""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3034,
                "RefundFeedItem.label: the shape FeedItem declares 'title' here; a branch maps the shape's fields "
                        + "one for one, by name, in declaration order"));
    }

    @Test
    void ac_proc_34_a_field_of_another_type_is_mq3034() {
        Compilation compilation = compilePair(SHAPE_FIELDS + ", BigDecimal amount", BRANCH_FIELDS + ", Long amount");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3034,
                "RefundFeedItem.amount: branch RefundFeedItem maps it as Long, the shape FeedItem as BigDecimal; a "
                        + "set operation compares the stored values"));
    }

    @Test
    void ac_proc_34_a_converter_the_shape_does_not_use_is_mq3034() {
        Compilation compilation = compileBranch("""
                @Discriminator("REFUND") @PrimaryKey String kind, @PrimaryKey Long id,
                @Column(attribute = "reason", converter = ReasonConverter.class) String title,
                @Column(attribute = "refundedAt") Instant at""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3034,
                "RefundFeedItem.title: branch RefundFeedItem converts it with ReasonConverter, the shape FeedItem "
                        + "with no converter; a set operation compares the stored values"));
    }

    @Test
    void ac_proc_34_an_enum_type_the_shape_does_not_share_is_mq3034() {
        Compilation compilation = compilePair(SHAPE_FIELDS + ", Status status", BRANCH_FIELDS + ", Status status");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3034,
                "RefundFeedItem.status: RefundEntity.status stores it as @Enumerated(ORDINAL), and OrderEntity.status "
                        + "as @Enumerated(STRING); a set operation compares the stored values"));
    }

    @Test
    void ac_proc_34_a_temporal_type_the_shape_does_not_share_is_mq3034() {
        Compilation compilation = compilePair(SHAPE_FIELDS + ", Date day", BRANCH_FIELDS + ", Date day");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3034,
                "RefundFeedItem.day: RefundEntity.day stores it as @Temporal(DATE), and OrderEntity.day as "
                        + "@Temporal(TIMESTAMP); a set operation compares the stored values"));
    }

    @Test
    void ac_proc_34_a_key_the_shape_does_not_share_is_mq3034() {
        // The shape's key is the discriminator and the id; the branch keys by the discriminator alone.
        Compilation compilation = compilePair(SHAPE_FIELDS, """
                @Discriminator("REFUND") @PrimaryKey String kind, Long id,
                @Column(attribute = "reason") String title, @Column(attribute = "refundedAt") Instant at""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(
                message(DiagnosticCode.MQ3031, "RefundFeedItem: @PrimaryKey must be RefundEntity's id 'id', one "
                        + "row's key; group the source to key it by its @GroupBy columns"),
                message(DiagnosticCode.MQ3034, "RefundFeedItem: @PrimaryKey must be the shape FeedItem's key 'kind', "
                        + "'id', by name and in order; the branches of a set operation are matched by the shape's "
                        + "key"));
    }

    @Test
    void ac_proc_34_a_window_field_on_a_branch_is_mq3034() {
        Compilation compilation = compileBranch("""
                @Discriminator("REFUND") @PrimaryKey String kind, @PrimaryKey Long id,
                @Column(attribute = "reason") String title, @Column(attribute = "refundedAt") Instant at,
                @Window(function = WindowFunction.ROW_NUMBER, orderBy = "reason") Long rank""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(
                message(DiagnosticCode.MQ3034, "RefundFeedItem.rank: a branch declares no @Window field; a window "
                        + "over the combined rows is not supported (R-PROC-38)"),
                message(DiagnosticCode.MQ3034, "RefundFeedItem.rank: the shape FeedItem declares no field at this "
                        + "position; a branch maps the shape's fields one for one, by name, in declaration order"));
    }

    @Test
    void ac_proc_34_a_window_field_on_a_shape_with_branches_is_mq3034() {
        Compilation compilation = compile(ORDER_ENTITY, REFUND_ENTITY, STATUS,
                feedItem("WindowedFeed", """
                        @Discriminator("ORDER") @PrimaryKey String kind, @PrimaryKey Long id,
                        String title, Instant at,
                        @Window(function = WindowFunction.ROW_NUMBER, orderBy = "title") Long rank"""),
                branch("WindowedRefund", "WindowedFeed", """
                        @Discriminator("REFUND") @PrimaryKey String kind, @PrimaryKey Long id,
                        @Column(attribute = "reason") String title, @Column(attribute = "refundedAt") Instant at"""));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(
                message(DiagnosticCode.MQ3034, "WindowedRefund: the shape WindowedFeed declares the @Window field "
                        + "rank, which no branch can combine with; a shape with branches declares no @Window "
                        + "(R-PROC-38)"),
                message(DiagnosticCode.MQ3034, "WindowedRefund: the shape WindowedFeed declares the field 'rank' "
                        + "after every field of the branch; a branch maps the shape's fields one for one, by name, "
                        + "in declaration order"));
    }

    @Test
    void ac_proc_34_a_branch_of_a_discriminated_shape_without_a_discriminator_is_mq3034() {
        Compilation compilation = compileBranch("""
                @PrimaryKey String kind, @PrimaryKey Long id,
                @Column(attribute = "reason") String title, @Column(attribute = "refundedAt") Instant at""");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(
                message(DiagnosticCode.MQ3034, "RefundFeedItem: the shape FeedItem carries @Discriminator on 'kind', "
                        + "and every branch declares the same; RefundFeedItem declares none (R-SRC-06)"),
                message(DiagnosticCode.MQ3031, "RefundFeedItem: @PrimaryKey must be RefundEntity's id 'id', one "
                        + "row's key; group the source to key it by its @GroupBy columns"));
    }

    @Test
    void ac_proc_34_a_discriminator_outside_the_key_is_mq3034() {
        Compilation compilation = compile(ORDER_ENTITY, REFUND_ENTITY, STATUS,
                feedItem("LooseFeed", """
                        @Discriminator("ORDER") String kind, @PrimaryKey Long id, String title, Instant at"""),
                branch("LooseRefund", "LooseFeed", """
                        @Discriminator("REFUND") String kind, @PrimaryKey Long id,
                        @Column(attribute = "reason") String title, @Column(attribute = "refundedAt") Instant at"""));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(
                message(DiagnosticCode.MQ3034, "LooseFeed.kind: @Discriminator must be part of the @PrimaryKey; a "
                        + "branch is keyed by the constant it fixes (R-SRC-06)"),
                message(DiagnosticCode.MQ3034, "LooseRefund.kind: @Discriminator must be part of the @PrimaryKey; a "
                        + "branch is keyed by the constant it fixes (R-SRC-06)"));
    }

    @Test
    void ac_proc_34_a_second_discriminator_column_is_mq3034() {
        Compilation compilation = compile(ORDER_ENTITY, REFUND_ENTITY, STATUS, feedItem("TwoKinds", """
                @Discriminator("ORDER") @PrimaryKey String kind, @Discriminator("ORDER") @PrimaryKey Long id,
                String title, Instant at"""));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(message(DiagnosticCode.MQ3034, "TwoKinds.id: a model declares one "
                + "@Discriminator field, the constant that tells its branches apart; the first is kind"));
    }

    @Test
    void ac_proc_34_a_discriminator_that_is_not_a_string_is_mq3034() {
        Compilation compilation = compile(ORDER_ENTITY, REFUND_ENTITY, STATUS, feedItem("NumericKind", """
                @PrimaryKey String kind, @Discriminator("1") @PrimaryKey Long id, String title, Instant at"""));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(message(DiagnosticCode.MQ3034, "NumericKind.id: @Discriminator "
                + "fixes a String constant, so the field is a String (R-SRC-06)"));
    }
}
