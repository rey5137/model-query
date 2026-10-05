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

/**
 * {@code @Computed}, a {@code @GroupBy} on a computed field, and {@code @Aggregate(expression)} (processor/30 §6,
 * processor/31 R-GEN-27), with their diagnostics (processor/32 MQ3018, MQ3019, MQ3208, widened MQ3005).
 */
class ComputedModelTest {

    private static final JavaFileObject ORDER_ENTITY = source("shop.OrderEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                String status;
                BigDecimal amount;
            }
            """);

    private static final JavaFileObject CUSTOMER_ENTITY = source("shop.CustomerEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
            }
            """);

    private static final String HEAD = """
            package shop;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.Child;
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Computed;
            import com.rey.modelquery.annotations.ExcludeFromDefaults;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.annotations.Transient;
            import java.math.BigDecimal;
            import java.util.Optional;

            """;

    /** {@code amount * 2}, typed to {@code OrderView}, with the {@code INSTANCE} the constant reads. */
    private static final JavaFileObject DOUBLED = definition("Doubled", "OrderView", "BigDecimal", """
            public static final Doubled INSTANCE = new Doubled();
            """);

    /** The same, with a public no-arg constructor and no {@code INSTANCE}. */
    private static final JavaFileObject FRESH_DOUBLED = definition("FreshDoubled", "OrderView", "BigDecimal", "");

    /** An {@code ExpressionDefinition<OrderView, String>}, for the wrong-type-argument branch. */
    private static final JavaFileObject STRING_DEF = definition("StringDef", "OrderView", "String", "");

    /** A class implementing no {@code ExpressionDefinition}. */
    private static final JavaFileObject NOT_A_DEFINITION =
            source("shop.NotADefinition", "package shop;\npublic final class NotADefinition {}\n");

    /** An {@code ExpressionDefinition<OrderView, BigDecimal>} with a private constructor and no {@code INSTANCE}. */
    private static final JavaFileObject PRIVATE_DEF = source("shop.PrivateDef", """
            package shop;

            import com.rey.modelquery.core.ExpressionDefinition;
            import com.rey.modelquery.core.ExpressionField;
            import java.math.BigDecimal;

            public final class PrivateDef implements ExpressionDefinition<OrderView, BigDecimal> {
                private PrivateDef() {
                }

                @Override
                public ExpressionField<OrderView, BigDecimal> expression() {
                    throw new UnsupportedOperationException();
                }
            }
            """);

    /**
     * A definition over {@code OrderEntity.amount * 2}, typed to {@code model}. {@code extra} is placed inside the
     * class, so a test can add an {@code INSTANCE} field.
     */
    private static JavaFileObject definition(String name, String model, String type, String extra) {
        return source("shop." + name, """
                package shop;

                import com.rey.modelquery.core.ColumnField;
                import com.rey.modelquery.core.Expr;
                import com.rey.modelquery.core.ExpressionDefinition;
                import com.rey.modelquery.core.ExpressionField;
                import com.rey.modelquery.core.TableField;
                import java.math.BigDecimal;

                public final class %s implements ExpressionDefinition<%s, %s> {
                    %s
                    @Override
                    public ExpressionField<%s, %s> expression() {
                        return Expr.times(ColumnField.of(%s.class, TableField.root(OrderEntity.class), "amount",
                                BigDecimal.class), BigDecimal.valueOf(2));
                    }
                }
                """.formatted(name, model, type, extra, model, type, model));
    }

    // ---- AC-PROC-13

    @Test
    void ac_proc_13_a_computed_constant_is_emitted_after_the_columns_in_all_and_default_and_mapped() {
        Compilation compilation = compile(ORDER_ENTITY, DOUBLED, source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public class OrderView {
                    @PrimaryKey
                    private Long id;
                    @Computed(Doubled.class)
                    private BigDecimal doubled;

                    public void setId(Long id) {
                    }

                    public void setDoubled(BigDecimal doubled) {
                    }
                }
                """));

        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderView");
        assertThat(generated)
                .contains("ExpressionField<OrderView, BigDecimal> DOUBLED = Doubled.INSTANCE.expression().named("
                        + "\"doubled\");")
                .contains("SelectSet<OrderView> ALL = SelectSet.of(ID, DOUBLED);")
                .contains("SelectSet<OrderView> DEFAULT = ALL;")
                .contains("if (row.isSelected(DOUBLED)) { m.setDoubled(row.get(DOUBLED)); }");
        int doubled = generated.indexOf(" DOUBLED = ");
        assertThat(doubled).isGreaterThan(generated.indexOf(" ID = ")).isLessThan(generated.indexOf(" ALL = "));
    }

    @Test
    void ac_proc_13_a_definition_without_instance_is_built_with_its_no_arg_constructor() {
        Compilation compilation = compile(ORDER_ENTITY, FRESH_DOUBLED, source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Computed(FreshDoubled.class) BigDecimal doubled) {}
                """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QOrderView")).contains("ExpressionField<OrderView, BigDecimal> "
                + "DOUBLED = new FreshDoubled().expression().named(\"doubled\");");
    }

    @Test
    void ac_proc_13_exclude_from_defaults_leaves_the_computed_field_out_of_default_only() {
        Compilation compilation = compile(ORDER_ENTITY, DOUBLED, source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(
                        @PrimaryKey Long id,
                        @ExcludeFromDefaults @Computed(Doubled.class) BigDecimal doubled) {}
                """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QOrderView"))
                .contains("SelectSet<OrderView> ALL = SelectSet.of(ID, DOUBLED);")
                .contains("SelectSet<OrderView> DEFAULT = ALL.without(DOUBLED);");
    }

    @Test
    void ac_proc_13_a_computed_constant_taking_a_reserved_name_is_mq3015() {
        Compilation compilation = compile(ORDER_ENTITY, DOUBLED, source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id,
                        @Computed(Doubled.class) BigDecimal groupKeys) {}
                """));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3015.code() + ": OrderView.groupKeys: constant "
                + "GROUP_KEYS is reserved by the generated class; rename the field");
    }

    // ---- AC-PROC-14

    /** A definition over {@code OrderEntity.status}, coalesced to a non-null key, typed to {@code OrderBand}. */
    private static final JavaFileObject BAND = source("shop.Band", """
            package shop;

            import com.rey.modelquery.core.ColumnField;
            import com.rey.modelquery.core.Expr;
            import com.rey.modelquery.core.ExpressionDefinition;
            import com.rey.modelquery.core.ExpressionField;
            import com.rey.modelquery.core.TableField;

            public final class Band implements ExpressionDefinition<OrderBand, String> {
                public static final Band INSTANCE = new Band();

                @Override
                public ExpressionField<OrderBand, String> expression() {
                    return Expr.coalesce(ColumnField.of(OrderBand.class, TableField.root(OrderEntity.class), "status",
                            String.class), "none");
                }
            }
            """);

    /** A definition over {@code OrderEntity.amount * 2}, typed to {@code OrderBand}. */
    private static final JavaFileObject BAND_DOUBLED = definition("BandDoubled", "OrderBand", "BigDecimal", """
            public static final BandDoubled INSTANCE = new BandDoubled();
            """);

    @Test
    void ac_proc_14_an_aggregate_over_an_expression_and_a_computed_group_key_are_generated() {
        Compilation compilation = compile(ORDER_ENTITY, BAND, BAND_DOUBLED, source("shop.OrderBand", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderBand(
                        @GroupBy @Computed(Band.class) String band,
                        @Aggregate(fn = AggregateFunction.SUM, expression = BandDoubled.class) BigDecimal doubled) {}
                """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QOrderBand"))
                .contains("ExpressionField<OrderBand, String> BAND = Band.INSTANCE.expression().named(\"band\");")
                .contains("AggregateField<OrderBand, BigDecimal> DOUBLED = "
                        + "Agg.sum(BandDoubled.INSTANCE.expression());")
                .contains("SelectSet<OrderBand> ALL = SelectSet.of(BAND);")
                .contains("SelectSet<OrderBand> GROUP_KEYS = SelectSet.of(BAND);")
                .contains("return ModelQuery.builder(ROOT, MAPPER).groupBy(GROUP_KEYS);")
                .contains("return new OrderBand(row.get(BAND), row.get(DOUBLED));");
    }

    @Test
    void ac_proc_14_count_distinct_and_a_32_bit_sum_over_an_expression_use_their_agg_overloads() {
        JavaFileObject longDef = source("shop.LongDef", """
                package shop;

                import com.rey.modelquery.core.Expr;
                import com.rey.modelquery.core.ExpressionDefinition;
                import com.rey.modelquery.core.ExpressionField;

                public final class LongDef implements ExpressionDefinition<OrderBand, Long> {
                    public static final LongDef INSTANCE = new LongDef();

                    @Override
                    public ExpressionField<OrderBand, Long> expression() {
                        return Expr.constant(1L);
                    }
                }
                """);
        JavaFileObject intDef = source("shop.IntDef", """
                package shop;

                import com.rey.modelquery.core.Expr;
                import com.rey.modelquery.core.ExpressionDefinition;
                import com.rey.modelquery.core.ExpressionField;

                public final class IntDef implements ExpressionDefinition<OrderBand, Integer> {
                    public static final IntDef INSTANCE = new IntDef();

                    @Override
                    public ExpressionField<OrderBand, Integer> expression() {
                        return Expr.constant(1);
                    }
                }
                """);
        Compilation compilation = compile(ORDER_ENTITY, BAND, longDef, intDef, source("shop.OrderBand", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderBand(
                        @GroupBy @Computed(Band.class) String band,
                        @Aggregate(fn = AggregateFunction.COUNT, distinct = true, expression = LongDef.class)
                                Long rows,
                        @Aggregate(fn = AggregateFunction.SUM, expression = IntDef.class) Long units) {}
                """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QOrderBand"))
                .contains("AggregateField<OrderBand, Long> ROWS = Agg.countDistinct(LongDef.INSTANCE.expression());")
                .contains("AggregateField<OrderBand, Long> UNITS = Agg.sumAsLong(IntDef.INSTANCE.expression());");
    }

    // ---- AC-DIAG-08

    private static final String RECORD_MODEL =
            "@QueryModel(root = OrderEntity.class)\n"
            + "public record OrderView(@PrimaryKey Long id, @Computed(%s) BigDecimal doubled) {}\n";

    @Test
    void ac_diag_08_a_class_that_is_not_an_expression_definition_is_mq3018() {
        Compilation compilation = compile(ORDER_ENTITY, NOT_A_DEFINITION,
                source("shop.OrderView", HEAD + RECORD_MODEL.formatted("NotADefinition.class")));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3018.code() + ": OrderView.doubled: NotADefinition "
                + "is not an ExpressionDefinition<OrderView, BigDecimal>, or has neither INSTANCE nor a no-arg "
                + "constructor");
    }

    @Test
    void ac_diag_08_a_wrong_type_argument_is_mq3018() {
        Compilation compilation = compile(ORDER_ENTITY, STRING_DEF,
                source("shop.OrderView", HEAD + RECORD_MODEL.formatted("StringDef.class")));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3018.code() + ": OrderView.doubled: StringDef is "
                + "not an ExpressionDefinition<OrderView, BigDecimal>, or has neither INSTANCE nor a no-arg "
                + "constructor");
    }

    @Test
    void ac_diag_08_no_instance_and_no_visible_constructor_is_mq3018() {
        Compilation compilation = compile(ORDER_ENTITY, PRIVATE_DEF,
                source("shop.OrderView", HEAD + RECORD_MODEL.formatted("PrivateDef.class")));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3018.code() + ": OrderView.doubled: PrivateDef is "
                + "not an ExpressionDefinition<OrderView, BigDecimal>, or has neither INSTANCE nor a no-arg "
                + "constructor");
    }

    private static JavaFileObject model(String field) {
        return source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, %s) {}
                """.formatted(field));
    }

    @Test
    void ac_diag_08_computed_beside_a_primary_key_is_mq3019() {
        Compilation compilation = compile(ORDER_ENTITY, DOUBLED,
                model("@PrimaryKey @Computed(Doubled.class) BigDecimal doubled"));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3019.code()
                + ": OrderView.doubled: @Computed can't be combined with @PrimaryKey");
    }

    @Test
    void ac_diag_08_computed_beside_a_column_is_mq3019() {
        Compilation compilation = compile(ORDER_ENTITY, DOUBLED,
                model("@Column @Computed(Doubled.class) BigDecimal doubled"));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3019.code()
                + ": OrderView.doubled: @Computed can't be combined with @Column");
    }

    @Test
    void ac_diag_08_computed_beside_a_transient_is_mq3019() {
        Compilation compilation = compile(ORDER_ENTITY, DOUBLED,
                model("@Transient @Computed(Doubled.class) BigDecimal doubled"));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3019.code()
                + ": OrderView.doubled: @Computed can't be combined with @Transient");
    }

    @Test
    void ac_diag_08_computed_beside_an_aggregate_is_mq3019() {
        Compilation compilation = compile(ORDER_ENTITY, DOUBLED, source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class, singleGroup = true)
                public record OrderView(
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "amount")
                        @Computed(Doubled.class) BigDecimal doubled) {}
                """));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3019.code()
                + ": OrderView.doubled: @Computed can't be combined with @Aggregate");
    }

    @Test
    void ac_diag_08_a_primitive_computed_field_is_mq3019() {
        JavaFileObject intDef = source("shop.IntDef", """
                package shop;

                import com.rey.modelquery.core.Expr;
                import com.rey.modelquery.core.ExpressionDefinition;
                import com.rey.modelquery.core.ExpressionField;

                public final class IntDef implements ExpressionDefinition<OrderView, Integer> {
                    public static final IntDef INSTANCE = new IntDef();

                    @Override
                    public ExpressionField<OrderView, Integer> expression() {
                        return Expr.constant(1);
                    }
                }
                """);
        Compilation compilation = compile(ORDER_ENTITY, intDef, source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id,
                        @Computed(IntDef.class) int doubled) {}
                """));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3019.code()
                + ": OrderView.doubled: @Computed field is primitive; an expression may be NULL");
    }

    @Test
    void ac_diag_08_computed_beside_a_join_is_mq3019() {
        Compilation compilation = compile(ORDER_ENTITY, CUSTOMER_ENTITY, DOUBLED, source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class, singleGroup = true)
                public record OrderView(
                        @Join @Computed(Doubled.class) Optional<String> customer) {}
                """));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3019.code()
                + ": OrderView.customer: @Computed can't be combined with @Join");
    }

    @Test
    void ac_diag_08_computed_beside_a_child_is_mq3019() {
        Compilation compilation = compile(ORDER_ENTITY, DOUBLED, source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id,
                        @Child @Computed(Doubled.class) java.util.List<String> children) {}
                """));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3019.code()
                + ": OrderView.children: @Computed can't be combined with @Child");
    }

    @Test
    void ac_diag_08_aggregate_with_both_attribute_and_expression_is_mq3208() {
        Compilation compilation = compile(ORDER_ENTITY, BAND_DOUBLED, source("shop.OrderBand", HEAD + """
                @QueryModel(root = OrderEntity.class, singleGroup = true)
                public record OrderBand(
                        @Aggregate(fn = AggregateFunction.SUM, attribute = "amount", expression = BandDoubled.class)
                                BigDecimal doubled) {}
                """));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3208.code()
                + ": OrderBand.doubled: @Aggregate takes attribute or expression, not both");
    }

    @Test
    void ac_diag_08_a_join_target_with_a_computed_field_is_mq3005() {
        JavaFileObject nameDef = source("shop.NameDef", """
                package shop;

                import com.rey.modelquery.core.Expr;
                import com.rey.modelquery.core.ExpressionDefinition;
                import com.rey.modelquery.core.ExpressionField;

                public final class NameDef implements ExpressionDefinition<CustomerView, String> {
                    public static final NameDef INSTANCE = new NameDef();

                    @Override
                    public ExpressionField<CustomerView, String> expression() {
                        return Expr.coalesce(ColumnField.of(CustomerView.class,
                                TableField.root(CustomerEntity.class), "name", String.class), "none");
                    }
                }
                """);
        JavaFileObject customerView = source("shop.CustomerView", HEAD + """
                @QueryModel(root = CustomerEntity.class)
                public record CustomerView(@PrimaryKey Long id, @Computed(NameDef.class) String name) {}
                """);
        JavaFileObject orderView = source("shop.OrderView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record OrderView(@PrimaryKey Long id, @Join Optional<CustomerView> customer) {}
                """);

        Compilation compilation = compile(CUSTOMER_ENTITY, ORDER_ENTITY, nameDef, customerView, orderView);

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3005.code() + ": OrderView.customer: @Join model "
                + "CustomerView has @Computed fields; a model with a computed field can't be joined");
    }

    @Test
    void ac_diag_08_a_wrong_result_type_over_an_expression_is_mq3202() {
        Compilation compilation = compile(ORDER_ENTITY, STRING_DEF, source("shop.OrderBand", HEAD + """
                @QueryModel(root = OrderEntity.class, singleGroup = true)
                public record OrderBand(
                        @Aggregate(fn = AggregateFunction.COUNT, expression = StringDef.class) Integer rows) {}
                """));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3202.code()
                + ": OrderBand.rows: COUNT returns Long, field is Integer");
    }

    @Test
    void ac_diag_08_a_32_bit_sum_over_an_expression_is_mq3205() {
        JavaFileObject intDef = source("shop.IntDef", """
                package shop;

                import com.rey.modelquery.core.Expr;
                import com.rey.modelquery.core.ExpressionDefinition;
                import com.rey.modelquery.core.ExpressionField;

                public final class IntDef implements ExpressionDefinition<OrderBand, Integer> {
                    public static final IntDef INSTANCE = new IntDef();

                    @Override
                    public ExpressionField<OrderBand, Integer> expression() {
                        return Expr.constant(1);
                    }
                }
                """);
        Compilation compilation = compile(ORDER_ENTITY, intDef, source("shop.OrderBand", HEAD + """
                @QueryModel(root = OrderEntity.class, singleGroup = true)
                public record OrderBand(
                        @Aggregate(fn = AggregateFunction.SUM, expression = IntDef.class) Integer units) {}
                """));

        assertThat(errors(compilation)).contains(DiagnosticCode.MQ3205.code()
                + ": OrderBand.units: SUM over Integer returns Long; declare the field as Long");
    }
}
