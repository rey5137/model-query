package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.compileAgainst;
import static com.rey.modelquery.processor.ProcessorHarness.compileToClasspath;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.criteria.JoinType;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code @SourceModel} and the models that read a source ({@code processor/30} §10, {@code processor/31} R-GEN-37,
 * {@code api/17}).
 */
class SourceModelTest {

    /** The classes of an earlier module, for the models a binding names (R-PROC-30). */
    @TempDir
    Path classes;

    private static final String HEADER = """
            package shop;

            """;

    private static final JavaFileObject ORDER_ENTITY = source("shop.OrderEntity", """
            package shop;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                @Column(nullable = false)
                String status;
                BigDecimal total;
            }
            """);

    private static final JavaFileObject ORDER_TOTALS = source("shop.OrderTotals", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;
            import java.math.BigDecimal;

            @SourceModel(root = OrderEntity.class)
            public record OrderTotals(@PrimaryKey Long id, String status, BigDecimal total) {}
            """);

    private static final JavaFileObject STATUS_TOTALS = source("shop.StatusTotals", """
            package shop;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;

            @SourceModel(root = OrderEntity.class)
            public record StatusTotals(
                    @PrimaryKey @GroupBy String status,
                    @Aggregate(fn = AggregateFunction.COUNT) Long orders) {}
            """);

    private static final JavaFileObject ORDER_ROW = source("shop.OrderRow", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.math.BigDecimal;

            @QueryModel(root = OrderTotals.class)
            public record OrderRow(@PrimaryKey Long id, String status, BigDecimal total) {}
            """);

    private static final JavaFileObject ORDER_ITEM_ENTITY = source("shop.OrderItemEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.JoinColumn;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class OrderItemEntity {
                @Id
                Long id;
                String code;
                @ManyToOne
                @JoinColumn(name = "order_id")
                OrderEntity order;
            }
            """);

    private static final JavaFileObject ORDER_ITEM_ROW = source("shop.OrderItemRow", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;

            @QueryModel(root = OrderItemEntity.class)
            public record OrderItemRow(@PrimaryKey Long id, String code) {}
            """);

    private static final JavaFileObject STATUS_ROW = source("shop.StatusRow", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;

            @QueryModel(root = StatusTotals.class)
            public record StatusRow(@PrimaryKey String status, Long orders) {}
            """);

    /** {@link #ORDER_ROW} without its {@code @QueryModel}, the model a binding names (R-PROC-28). */
    private static final JavaFileObject ORDER_ROW_WITHOUT_ANNOTATION = source("shop.OrderRow", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;
            import java.math.BigDecimal;

            public record OrderRow(@PrimaryKey Long id, String status, BigDecimal total) {}
            """);

    private static Compilation compileShop() {
        return compile(ORDER_ENTITY, ORDER_TOTALS, STATUS_TOTALS, ORDER_ROW, STATUS_ROW);
    }

    private static JavaFileObject model(String name, String text) {
        return source("shop." + name, HEADER + text);
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }

    @Test
    void ac_proc_35_an_ungrouped_source_model_matches_its_golden_file() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QOrderTotals")).isEqualTo(resource("golden/QOrderTotals.java"));
    }

    @Test
    void ac_proc_35_a_grouped_source_model_matches_its_golden_file() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QStatusTotals")).isEqualTo(resource("golden/QStatusTotals.java"));
    }

    @Test
    void ac_proc_35_a_source_model_generates_no_mapper_and_no_write_member() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QStatusTotals"))
                .contains("public static SourceQuery.Builder<OrderEntity, StatusTotals, StatusTotals> source()",
                        "SourceQuery.builder(StatusTotals.class, ROOT, ALL, KEY, GROUP_KEYS)")
                .doesNotContain("MAPPER", "map(Row row)", "delete()", "update(", "insert", "Changes");
    }

    @Test
    void ac_proc_35_a_join_on_a_model_rooted_at_a_source_is_mq3035() {
        Compilation compilation = compile(ORDER_ENTITY, ORDER_TOTALS, ORDER_ROW, model("JoinRow", """
                import com.rey.modelquery.annotations.Join;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import java.util.Optional;

                @QueryModel(root = OrderTotals.class)
                public record JoinRow(@PrimaryKey Long id,
                        @Join(attribute = "total") Optional<OrderRow> total) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3035, "JoinRow.total: @Join needs"
                + " an entity root; OrderTotals is a @SourceModel, whose columns a model reads by field name"));
    }

    @Test
    void ac_proc_36_an_outer_model_over_a_source_matches_its_golden_file() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QOrderRow")).isEqualTo(resource("golden/QOrderRow.java"));
        assertThat(generated(compilation, "shop.QStatusRow")).isEqualTo(resource("golden/QStatusRow.java"));
    }

    @Test
    void ac_proc_36_a_query_model_rooted_at_a_source_reads_it_with_over_and_takes_its_source() {
        Compilation compilation = compileShop();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QStatusRow"))
                .contains("TableField<StatusTotals, StatusTotals> ROOT = TableField.source(StatusTotals.class)",
                        "ColumnField.over(StatusRow.class, ROOT, QStatusTotals.STATUS).named(\"status\")",
                        "public static ModelQuery.Builder<StatusTotals, String, StatusRow> query("
                                + " SourceQuery<StatusTotals> source)",
                        "ModelQuery.builder(ROOT, MAPPER, source).primaryKey(KEY)")
                .doesNotContain("TableField.root(StatusTotals.class)", "delete()", "update(");
    }

    @Test
    void ac_proc_36_an_outer_models_key_that_is_not_its_sources_key_is_mq3036() {
        Compilation wrongKey = compile(ORDER_ENTITY, ORDER_TOTALS, model("WrongKeyRow", """
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderTotals.class)
                public record WrongKeyRow(@PrimaryKey String status, Long id) {}
                """));
        Compilation noKey = compile(ORDER_ENTITY, ORDER_TOTALS, model("NoKeyRow", """
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderTotals.class)
                public record NoKeyRow(Long id, String status) {}
                """));

        assertThat(wrongKey).failed();
        assertThat(errors(wrongKey)).containsExactly(message(DiagnosticCode.MQ3036, "WrongKeyRow: @PrimaryKey must"
                + " be OrderTotals's key 'id', the source's own key; a keyset cursor and the engine's tie-breakers"
                + " read it"));
        assertThat(noKey).failed();
        assertThat(errors(noKey)).containsExactly(message(DiagnosticCode.MQ3036, "NoKeyRow: @PrimaryKey must"
                + " be OrderTotals's key 'id', the source's own key; a keyset cursor and the engine's tie-breakers"
                + " read it"));
    }

    @Test
    void ac_proc_36_an_outer_field_naming_no_source_field_is_mq3001_and_one_of_another_type_is_mq3002() {
        Compilation unknown = compile(ORDER_ENTITY, ORDER_TOTALS, model("UnknownRow", """
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderTotals.class)
                public record UnknownRow(@PrimaryKey Long id, Long amount) {}
                """));
        Compilation mistyped = compile(ORDER_ENTITY, ORDER_TOTALS, model("MistypedRow", """
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderTotals.class)
                public record MistypedRow(@PrimaryKey Long id, String total) {}
                """));

        assertThat(unknown).failed();
        assertThat(errors(unknown)).containsExactly(message(DiagnosticCode.MQ3001, "UnknownRow.amount: 'amount' is"
                + " no field of OrderTotals, the source this model reads; an outer model reads a source's fields by"
                + " name"));
        assertThat(mistyped).failed();
        assertThat(errors(mistyped)).containsExactly(message(DiagnosticCode.MQ3002,
                "MistypedRow.total: model type String, the source's total is BigDecimal"));
    }

    @Test
    void ac_proc_38_a_root_that_is_neither_an_entity_nor_a_source_model_is_mq3038() {
        Compilation compilation = compile(source("shop.Plain", HEADER + "public class Plain {}\n"),
                model("NeitherRow", """
                        import com.rey.modelquery.annotations.QueryModel;

                        @QueryModel(root = Plain.class)
                        public record NeitherRow(Long id) {}
                        """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3038,
                "NeitherRow: root Plain is neither @Entity nor @SourceModel"));
    }

    @Test
    void ac_proc_38_a_root_that_is_both_an_entity_and_a_source_model_is_mq3038() {
        Compilation compilation = compile(ORDER_ENTITY, source("shop.BothSource", HEADER + """
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import jakarta.persistence.Entity;

                @Entity
                @SourceModel(root = OrderEntity.class)
                public record BothSource(@PrimaryKey Long id) {}
                """), model("BothRow", """
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = BothSource.class)
                public record BothRow(Long id) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3038,
                "BothRow: root BothSource is both @Entity and @SourceModel; keep one"));
    }

    @Test
    void ac_proc_30_a_child_on_a_source_model_is_mq3030_and_generates_no_file() {
        var processor = new ProcessorHarness.Recording();
        Compilation compilation = processor.compile(ORDER_ENTITY, ORDER_TOTALS, ORDER_ROW, model("ChildTotals", """
                import com.rey.modelquery.annotations.Child;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import java.util.List;

                @SourceModel(root = OrderEntity.class)
                public record ChildTotals(@PrimaryKey Long id, @Child List<OrderRow> rows) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3030, "ChildTotals.rows: @Child"
                + " needs a parent query to fill it; a @SourceModel is one statement"));
        assertThat(processor.originating()).doesNotContainKey("shop.QChildTotals");
    }

    @Test
    void ac_proc_31_a_source_key_that_is_not_the_root_id_is_mq3031() {
        Compilation compilation = compile(ORDER_ENTITY, model("StatusKeyTotals", """
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;

                @SourceModel(root = OrderEntity.class)
                public record StatusKeyTotals(@PrimaryKey String status, Long id) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031, "StatusKeyTotals: @PrimaryKey"
                + " must be OrderEntity's id 'id'; group the source to key it by its @GroupBy"
                + " columns"));
    }

    /** An entity whose id is an {@code @IdClass}: the source keys it by both id columns (D-149, R-SRC-02). */
    private static final JavaFileObject STOCK_ENTITY = source("shop.StockEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.IdClass;

            @Entity
            @IdClass(StockId.class)
            public class StockEntity {
                @Id
                Long warehouseId;
                @Id
                Long productId;
                Integer quantity;
            }
            """);

    private static final JavaFileObject STOCK_ID = source("shop.StockId", """
            package shop;

            import java.io.Serializable;

            public class StockId implements Serializable {
                Long warehouseId;
                Long productId;
            }
            """);

    @Test
    void ac_proc_31_a_source_keyed_by_a_composite_id_compiles() {
        Compilation compilation = compile(STOCK_ENTITY, STOCK_ID, model("WarehouseStock", """
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;

                @SourceModel(root = StockEntity.class)
                public record WarehouseStock(
                        @PrimaryKey Long warehouseId, @PrimaryKey Long productId, Integer quantity) {}
                """));

        assertThat(compilation).succeeded();
    }

    @Test
    void ac_proc_31_a_source_key_that_is_part_of_a_composite_id_is_mq3031() {
        Compilation compilation = compile(STOCK_ENTITY, STOCK_ID, model("WarehouseStock", """
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;

                @SourceModel(root = StockEntity.class)
                public record WarehouseStock(@PrimaryKey Long warehouseId, Integer quantity) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031, "WarehouseStock: @PrimaryKey"
                + " must be StockEntity's id 'productId', 'warehouseId'; group the source to key it by its @GroupBy"
                + " columns"));
    }

    @Test
    void ac_proc_31_two_primary_keys_on_one_id_column_is_mq3031() {
        // Each id column is named by exactly one @PrimaryKey (D-149): a second key field over the same column would
        // make a one-column id look composite.
        Compilation compilation = compile(ORDER_ENTITY, model("DoubleKeySource", """
                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;

                @SourceModel(root = OrderEntity.class)
                public record DoubleKeySource(
                        @PrimaryKey Long id, @PrimaryKey @Column(attribute = "id") Long orderId, String status) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031, "DoubleKeySource: @PrimaryKey"
                + " must be OrderEntity's id 'id'; group the source to key it by its @GroupBy columns"));
    }

    @Test
    void ac_proc_31_a_source_key_that_is_not_the_full_group_set_is_mq3031() {
        Compilation compilation = compile(ORDER_ENTITY, model("HalfGroupTotals", """
                import com.rey.modelquery.annotations.Aggregate;
                import com.rey.modelquery.annotations.AggregateFunction;
                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import java.math.BigDecimal;

                @SourceModel(root = OrderEntity.class)
                public record HalfGroupTotals(
                        @PrimaryKey @GroupBy String status,
                        @GroupBy BigDecimal total,
                        @Aggregate(fn = AggregateFunction.COUNT) Long orders) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031, "HalfGroupTotals: @PrimaryKey"
                + " must be every @GroupBy column of the source, 'status', 'total', since a group is one row"));
    }

    @Test
    void ac_proc_31_a_group_key_that_is_nullable_is_mq3031() {
        Compilation compilation = compile(ORDER_ENTITY, model("TotalGroupTotals", """
                import com.rey.modelquery.annotations.Aggregate;
                import com.rey.modelquery.annotations.AggregateFunction;
                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import java.math.BigDecimal;

                @SourceModel(root = OrderEntity.class)
                public record TotalGroupTotals(
                        @PrimaryKey @GroupBy BigDecimal total,
                        @Aggregate(fn = AggregateFunction.COUNT) Long orders) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031, "TotalGroupTotals.total: a"
                + " group key must be non-nullable, and total is nullable; add @Column(nullable = false) or an"
                + " @Id"));
    }

    @Test
    void ac_proc_31_a_field_whose_path_crosses_a_to_many_join_is_mq3031() {
        Compilation compilation = compile(
                source("shop.LineEntity", HEADER + """
                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;
                        import java.math.BigDecimal;

                        @Entity
                        public class LineEntity {
                            @Id
                            Long id;
                            BigDecimal price;
                        }
                        """),
                source("shop.LinesEntity", HEADER + """
                        import jakarta.persistence.Column;
                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;
                        import jakarta.persistence.OneToMany;
                        import java.util.List;

                        @Entity
                        public class LinesEntity {
                            @Id
                            Long id;
                            @Column(nullable = false)
                            String status;
                            @OneToMany
                            List<LineEntity> lines;
                        }
                        """),
                model("LineTotals", """
                        import com.rey.modelquery.annotations.FilterColumn;
                        import com.rey.modelquery.annotations.JoinKind;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.SourceModel;

                        @SourceModel(root = LinesEntity.class)
                        @FilterColumn(name = "LINES", path = "lines.price", joinType = JoinKind.INNER)
                        public record LineTotals(@PrimaryKey Long id, String status) {}
                        """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3031, "LineTotals"
                + " @FilterColumn(LINES): a source reads one row per key, so its fields can't cross the to-many join"
                + " 'lines'; filter with Filters.exists"));
    }

    @Test
    void ac_proc_36_a_filter_column_of_an_outer_model_is_mq3011() {
        Compilation compilation = compile(ORDER_ENTITY, ORDER_TOTALS, model("FilteredRow", """
                import com.rey.modelquery.annotations.FilterColumn;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderTotals.class)
                @FilterColumn(name = "STATUS_RAW", path = "status")
                public record FilteredRow(@PrimaryKey Long id) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3011, "FilteredRow"
                + " @FilterColumn(STATUS_RAW): an outer model reads a source's fields by field name; a @FilterColumn"
                + " of its own has no CTE column to alias"));
    }

    @Test
    void ac_proc_36_a_converter_on_an_outer_column_is_mq3014() {
        JavaFileObject converter = source("shop.StatusTextConverter", HEADER + """
                import com.rey.modelquery.core.ColumnConverter;

                public final class StatusTextConverter implements ColumnConverter<String, String> {
                    public static final StatusTextConverter INSTANCE = new StatusTextConverter();

                    @Override
                    public String toModel(String attribute) {
                        return attribute.toLowerCase();
                    }

                    @Override
                    public String toAttribute(String model) {
                        return model.toUpperCase();
                    }
                }
                """);
        Compilation compilation = compile(ORDER_ENTITY, ORDER_TOTALS, converter, model("ConvertedRow", """
                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderTotals.class)
                public record ConvertedRow(@PrimaryKey Long id,
                        @Column(converter = StatusTextConverter.class) String status) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3014, "ConvertedRow.status: an outer"
                + " column reads its source's converter; drop @Column(converter = ...) or declare it on the source's"
                + " own field"));
    }

    @Test
    void ac_proc_39_a_child_whose_child_model_is_rooted_at_a_source_is_mq3039() {
        Compilation compilation = compile(ORDER_ENTITY, ORDER_TOTALS, ORDER_ROW, model("ParentRow", """
                import com.rey.modelquery.annotations.Child;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import java.util.List;

                @QueryModel(root = OrderEntity.class)
                public record ParentRow(@PrimaryKey Long id, @Child(foreignKey = "id") List<OrderRow> totals) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3039, "ParentRow.totals:"
                + " OrderRow.root is OrderTotals, a @SourceModel; a fetch plan has no source to pass it"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac_proc_39_a_child_on_a_model_rooted_at_a_source_is_keyed_on_a_source_field() throws Exception {
        Compilation compilation = compile(ORDER_ENTITY, ORDER_TOTALS, ORDER_ITEM_ENTITY, ORDER_ITEM_ROW,
                model("OrderFeed", """
                        import com.rey.modelquery.annotations.Child;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;
                        import java.util.List;

                        @QueryModel(root = OrderTotals.class)
                        public record OrderFeed(@PrimaryKey Long id,
                                @Child(key = "id", foreignKey = "order.id") List<OrderItemRow> items) {}
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        // The parent-side key reads the source's own column through its CTE, not an entity metamodel (R-SRC-10);
        // ColumnField.of(...) would be MQ1905 on a source root, so the generated key names the source's constant.
        assertThat(generated(compilation, "shop.QOrderFeed").replaceAll("\\s+", " "))
                .contains("ColumnField.over(OrderFeed.class, ROOT, QOrderTotals.ID)");

        ClassLoader loader = classes(compilation);
        Class<?> qFeed = loader.loadClass("shop.QOrderFeed");
        var items = (ChildField<Object, Object>) constant(qFeed, "ITEMS");
        assertThat(items.isToMany()).isTrue();
        assertThat(items.key()).isEqualTo(constant(qFeed, "ID"));
        assertThat(items.foreignKey()).contains(ColumnField.of(
                (Class<Object>) loader.loadClass("shop.OrderItemRow"),
                TableField.join(TableField.root(loader.loadClass("shop.OrderItemEntity")), "order", JoinType.LEFT),
                "id", Long.class));
    }

    @Test
    void ac_proc_39_an_unknown_parent_side_key_name_on_a_source_parent_is_mq3402() {
        Compilation compilation = compile(ORDER_ENTITY, ORDER_TOTALS, ORDER_ITEM_ENTITY, ORDER_ITEM_ROW,
                model("BadKeyRow", """
                        import com.rey.modelquery.annotations.Child;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;
                        import java.util.List;

                        @QueryModel(root = OrderTotals.class)
                        public record BadKeyRow(@PrimaryKey Long id,
                                @Child(key = "nope", foreignKey = "order.id") List<OrderItemRow> items) {}
                        """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3402,
                "BadKeyRow.items: OrderTotals has no attribute 'nope'"));
    }

    @Test
    void ac_proc_39_a_through_child_on_a_source_parent_is_mq3406() {
        Compilation compilation = compile(ORDER_ENTITY, ORDER_TOTALS, ORDER_ITEM_ENTITY, ORDER_ITEM_ROW,
                model("ThroughRow", """
                        import com.rey.modelquery.annotations.Child;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;
                        import java.util.List;

                        @QueryModel(root = OrderTotals.class)
                        public record ThroughRow(@PrimaryKey Long id,
                                @Child(key = "id", through = "items") List<OrderItemRow> items) {}
                        """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3406, "ThroughRow.items: through "
                + "'items' needs an entity root to join along, and ThroughRow is rooted at OrderTotals, a "
                + "@SourceModel; use foreignKey"));
    }

    @Test
    void ac_proc_40_generate_changes_on_a_model_rooted_at_a_source_is_mq3040() {
        Compilation compilation = compile(ORDER_ENTITY, ORDER_TOTALS, model("ChangesRow", """
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = OrderTotals.class, generateChanges = true)
                public record ChangesRow(@PrimaryKey Long id, String status) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3040, "ChangesRow: generateChanges"
                + " needs an entity root; it is rooted at source OrderTotals, a @SourceModel no change set can write"));
    }

    @Test
    void ac_proc_40_generate_changes_on_a_bound_query_model_rooted_at_a_source_is_mq3040() {
        compileToClasspath(classes, ORDER_ENTITY, ORDER_TOTALS, ORDER_ROW_WITHOUT_ANNOTATION);
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = compileAgainst(classes, processor, ORDER_ENTITY, source(
                "persistence.ChangesModels", """
                        package persistence;

                        import com.rey.modelquery.annotations.BindQueryModel;
                        import shop.OrderRow;
                        import shop.OrderTotals;

                        @BindQueryModel(model = OrderRow.class, root = OrderTotals.class, generateChanges = true)
                        final class ChangesModels {}
                        """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3040, "OrderRow: generateChanges"
                + " needs an entity root; it is rooted at source OrderTotals, a @SourceModel no change set can write"));
    }

    /** The public static constant {@code name} of the generated {@code qModel}. */
    private static Object constant(Class<?> qModel, String name) throws ReflectiveOperationException {
        return qModel.getField(name).get(null);
    }
}
