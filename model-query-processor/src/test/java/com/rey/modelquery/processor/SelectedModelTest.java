package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.RowSelection;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import jakarta.persistence.Tuple;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code @Selected} (processor/30 R-PROC-25, processor/31 R-GEN-29 and R-GEN-30): what the processor emits for a
 * record, a class and nested models, and what the generated mapper does with a row; and its diagnostics
 * (processor/32 {@code MQ3020}, {@code MQ3021}, {@code MQ3302}, {@code MQ3502}), whose message text the diagnostic
 * matrix pins.
 */
class SelectedModelTest {

    private static final String HEAD = """
            package sel;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Computed;
            import com.rey.modelquery.annotations.FilterColumn;
            import com.rey.modelquery.annotations.InsertModel;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.annotations.Selected;
            import com.rey.modelquery.annotations.Transient;
            import com.rey.modelquery.annotations.UpdateModel;
            import com.rey.modelquery.core.SelectSet;
            import java.math.BigDecimal;
            import java.util.Optional;

            """;

    private static final JavaFileObject CUSTOMER_ENTITY = source("sel.CustomerEntity", """
            package sel;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
            }
            """);

    private static final JavaFileObject ORDER_ENTITY = source("sel.OrderEntity", """
            package sel;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;
            import java.math.BigDecimal;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                String status;
                BigDecimal amount;
                @ManyToOne
                CustomerEntity customer;
            }
            """);

    /** {@code amount * 2}, the expression behind {@code OrderView.doubled}. */
    private static final JavaFileObject DOUBLED = source("sel.Doubled", """
            package sel;

            import com.rey.modelquery.core.ColumnField;
            import com.rey.modelquery.core.Expr;
            import com.rey.modelquery.core.ExpressionDefinition;
            import com.rey.modelquery.core.ExpressionField;
            import com.rey.modelquery.core.TableField;
            import java.math.BigDecimal;

            public final class Doubled implements ExpressionDefinition<OrderView, BigDecimal> {
                public static final Doubled INSTANCE = new Doubled();

                @Override
                public ExpressionField<OrderView, BigDecimal> expression() {
                    return Expr.times(ColumnField.of(OrderView.class, TableField.root(OrderEntity.class), "amount",
                            BigDecimal.class), BigDecimal.valueOf(2));
                }
            }
            """);

    private static final JavaFileObject CUSTOMER_VIEW = source("sel.CustomerView", HEAD + """
            @QueryModel(root = CustomerEntity.class)
            public record CustomerView(
                    @PrimaryKey Long id, String name, @Selected SelectSet<CustomerView> selected) {}
            """);

    /** A record: a column, a computed expression, a join, a filter-only column and the {@code @Selected} component. */
    private static final JavaFileObject ORDER_VIEW = source("sel.OrderView", HEAD + """
            @QueryModel(root = OrderEntity.class)
            @FilterColumn(name = "AMOUNT_FILTER", path = "amount")
            public record OrderView(
                    @PrimaryKey Long id,
                    String status,
                    @Computed(Doubled.class) BigDecimal doubled,
                    @Join Optional<CustomerView> customer,
                    @Selected SelectSet<OrderView> selected) {}
            """);

    private static final JavaFileObject CUSTOMER_CARD = source("sel.CustomerCard", HEAD + """
            @QueryModel(root = CustomerEntity.class)
            public class CustomerCard {
                @PrimaryKey
                private Long id;
                private String name = "unnamed";
                @Selected
                private SelectSet<CustomerCard> selected;

                public void setId(Long id) { this.id = id; }
                public void setName(String name) { this.name = name; }
                public void setSelected(SelectSet<CustomerCard> selected) { this.selected = selected; }
            }
            """);

    /** A class: an initialised field, a join of a class with a {@code @Selected} field of its own, and its own. */
    private static final JavaFileObject ORDER_CARD = source("sel.OrderCard", HEAD + """
            @QueryModel(root = OrderEntity.class)
            public class OrderCard {
                @PrimaryKey
                private Long id;
                private String status = "NEW";
                @Join
                private Optional<CustomerCard> customer;
                @Selected
                private SelectSet<OrderCard> picked;

                public void setId(Long id) { this.id = id; }
                public void setStatus(String status) { this.status = status; }
                public void setCustomer(Optional<CustomerCard> customer) { this.customer = customer; }
                public Optional<CustomerCard> getCustomer() { return customer; }
                public void setPicked(SelectSet<OrderCard> picked) { this.picked = picked; }
            }
            """);

    /** A whole-table aggregate with no key, which fills its set with the aggregate it reads. */
    private static final JavaFileObject ORDER_STATS = source("sel.OrderStats", HEAD + """
            @QueryModel(root = OrderEntity.class, singleGroup = true)
            public record OrderStats(
                    @Aggregate(fn = AggregateFunction.COUNT) Long lines,
                    @Selected SelectSet<OrderStats> selected) {}
            """);

    /** A join field named like the generated set: its scoped-row local would shadow a bare reference to it. */
    private static final JavaFileObject SHADOW_VIEW = source("sel.ShadowView", HEAD + """
            @QueryModel(root = OrderEntity.class)
            public record ShadowView(
                    @PrimaryKey Long id,
                    @Join(attribute = "customer", prefix = "BUYER") Optional<CustomerView> SELECTED_FIELDS,
                    @Selected SelectSet<ShadowView> selected) {}
            """);

    private static final JavaFileObject PLAIN_VIEW = source("sel.PlainView", HEAD + """
            @QueryModel(root = OrderEntity.class)
            public record PlainView(
                    @PrimaryKey Long id,
                    @Column(attribute = "status") String selectedFields,
                    @Column(attribute = "status") String selected) {}
            """);

    private static Compilation compilation;
    private static ClassLoader loader;

    @BeforeAll
    static void compileModels() {
        compilation = compile(CUSTOMER_ENTITY, ORDER_ENTITY, DOUBLED, CUSTOMER_VIEW, ORDER_VIEW, CUSTOMER_CARD,
                ORDER_CARD, ORDER_STATS, SHADOW_VIEW, PLAIN_VIEW);
        loader = classes(compilation);
    }

    // ---- AC-PROC-18

    @Test
    void ac_proc_18_a_selected_component_is_no_column_constant_set_member_or_key() {
        assertThat(compilation).succeededWithoutWarnings();
        String source = generatedFlat(compilation, "sel.QOrderView");

        assertThat(source)
                .contains("SelectSet<OrderView> ALL = SelectSet.of(ID, STATUS, DOUBLED);")
                .contains("SelectSet<OrderView> DEFAULT = ALL;")
                .contains("PrimaryKey<OrderView, Long> KEY = PrimaryKey.of(ID);")
                .doesNotContain(" SELECTED = ")
                .doesNotContain("ColumnField<OrderView, OrderEntity, SelectSet");
    }

    @Test
    void ac_proc_18_a_selected_field_is_mapped_by_a_record_and_by_a_class_model() {
        assertThat(generatedFlat(compilation, "sel.QOrderView"))
                .contains("QOrderView.SELECTED_FIELDS.selectedIn(row))");
        assertThat(generatedFlat(compilation, "sel.QOrderCard"))
                .contains("QOrderCard.SELECTED_FIELDS.selectedIn(row))");
    }

    // ---- AC-GEN-15

    @Test
    void ac_gen_15_the_set_holds_every_field_the_mapper_reads_and_is_declared_after_every_other_constant() {
        String source = generatedFlat(compilation, "sel.QOrderView");

        String declaration = "private static final SelectSet<OrderView> SELECTED_FIELDS = SelectSet.of("
                + "ID, STATUS, CUSTOMER_ID, CUSTOMER_NAME, DOUBLED);";
        assertThat(source).contains(declaration);
        // Nothing is declared after it but the mapper, which only names a method: no constant it holds is read early.
        String after = source.substring(source.indexOf(declaration) + declaration.length());
        assertThat(after.split("static final", -1)).hasSize(2);
        assertThat(after).startsWith(" public static final RowMapper<OrderView> MAPPER");
        // A filter-only column and the @Selected component are in no column constant, so not in it.
        assertThat(declaration).doesNotContain("AMOUNT_FILTER");
    }

    @Test
    void ac_gen_15_an_aggregate_model_holds_its_aggregate_and_a_plain_model_generates_no_set() {
        assertThat(generatedFlat(compilation, "sel.QOrderStats"))
                .contains("private static final SelectSet<OrderStats> SELECTED_FIELDS = SelectSet.of(LINES);");
        // A model with no @Selected loses no constant name: its selectedFields field is an ordinary column.
        assertThat(generatedFlat(compilation, "sel.QPlainView"))
                .contains("ColumnField<PlainView, OrderEntity, String> SELECTED_FIELDS = ")
                .doesNotContain("SelectSet<PlainView> SELECTED_FIELDS");
    }

    @Test
    void ac_gen_15_a_field_taking_the_constant_name_is_mq3015_on_a_selected_model_only() {
        Compilation clash = compile(CUSTOMER_ENTITY, ORDER_ENTITY, source("sel.ClashView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record ClashView(
                        @PrimaryKey Long id,
                        @Column(attribute = "status") String selectedFields,
                        @Selected SelectSet<ClashView> selected) {}
                """));

        assertThat(errors(clash)).containsExactly("MQ3015: ClashView.selectedFields: constant SELECTED_FIELDS is "
                + "reserved by the generated class; rename the field");
    }

    // ---- AC-GEN-16

    @Test
    void ac_gen_16_a_record_takes_the_set_as_its_last_argument() {
        assertThat(generatedFlat(compilation, "sel.QOrderView"))
                .contains("QOrderView.SELECTED_FIELDS.selectedIn(row));")
                .contains("return new OrderView(row.get(ID), row.get(STATUS), row.get(DOUBLED), "
                        + "customer.get(QCustomerView.ID) == null ? Optional.empty() : "
                        + "Optional.of(QCustomerView.MAPPER.map(customer)), "
                        + "QOrderView.SELECTED_FIELDS.selectedIn(row));");
    }

    @Test
    void ac_gen_16_a_class_setter_is_always_called() {
        String source = generatedFlat(compilation, "sel.QOrderCard");

        assertThat(source).contains(" m.setPicked(QOrderCard.SELECTED_FIELDS.selectedIn(row)); ");
        // Not behind a row.isSelected test, as the setter of a column is (R-GEN-09).
        assertThat(source).doesNotContain("isSelected(PICKED").doesNotContain("PICKED");
    }

    @Test
    void ac_gen_16_a_record_gets_the_columns_the_row_selected_and_a_selected_null_counts() throws Exception {
        Class<?> qModel = loader.loadClass("sel.QOrderView");
        // Selected: the key, a status that is NULL, and the customer's id (a LEFT-join hit); not the name.
        Row row = row(qModel, "ID", 7L, "STATUS", null, "CUSTOMER_ID", 3L);

        Object order = mapper(qModel).map(row);

        assertThat(fields(call(order, "selected"))).containsExactly(constant(qModel, "ID"),
                constant(qModel, "STATUS"), constant(qModel, "CUSTOMER_ID"));
        assertThat(call(order, "status")).isNull();
        assertThat(call(order, "doubled")).isNull();
    }

    @Test
    void ac_gen_16_a_nested_model_fills_its_own_set_from_its_scoped_row() throws Exception {
        Class<?> qModel = loader.loadClass("sel.QOrderView");
        Class<?> qCustomer = loader.loadClass("sel.QCustomerView");
        Row row = row(qModel, "ID", 7L, "CUSTOMER_ID", 3L, "CUSTOMER_NAME", "Ada");

        Object order = mapper(qModel).map(row);

        Object customer = ((Optional<?>) call(order, "customer")).orElseThrow();
        assertThat(call(customer, "name")).isEqualTo("Ada");
        // The nested model's own set holds its own columns, not the outer model's joined ones.
        assertThat(fields(call(customer, "selected")))
                .containsExactly(constant(qCustomer, "ID"), constant(qCustomer, "NAME"));
        assertThat(fields(call(order, "selected"))).containsExactly(constant(qModel, "ID"),
                constant(qModel, "CUSTOMER_ID"), constant(qModel, "CUSTOMER_NAME"));
    }

    @Test
    void ac_gen_16_an_empty_optional_builds_no_nested_model_and_the_outer_set_is_still_filled() throws Exception {
        Class<?> qModel = loader.loadClass("sel.QOrderView");
        // A LEFT-join miss: the customer's key was selected and is NULL.
        Row row = row(qModel, "ID", 7L, "CUSTOMER_ID", null, "CUSTOMER_NAME", null);

        Object order = mapper(qModel).map(row);

        assertThat((Optional<?>) call(order, "customer")).isEmpty();
        assertThat(fields(call(order, "selected"))).hasSize(3);
    }

    @Test
    void ac_gen_16_a_class_model_is_given_a_set_even_when_nothing_else_is_selected() throws Exception {
        Class<?> qModel = loader.loadClass("sel.QOrderCard");
        Row row = row(qModel, "ID", 7L, "CUSTOMER_ID", 3L, "CUSTOMER_NAME", null);

        Object order = mapper(qModel).map(row);

        assertThat(read(order, "status")).isEqualTo("NEW");
        assertThat(fields(read(order, "picked"))).containsExactly(constant(qModel, "ID"),
                constant(qModel, "CUSTOMER_ID"), constant(qModel, "CUSTOMER_NAME"));
        Object customer = ((Optional<?>) read(order, "customer")).orElseThrow();
        // The nested class model is filled the same way; its name was selected NULL, so its setter ran.
        assertThat(read(customer, "name")).isNull();
        Class<?> qCustomer = loader.loadClass("sel.QCustomerCard");
        assertThat(fields(read(customer, "selected"))).containsExactly(constant(qCustomer, "ID"),
                constant(qCustomer, "NAME"));
    }

    @Test
    void ac_gen_16_a_join_local_named_like_the_set_does_not_shadow_it() throws Exception {
        assertThat(generatedFlat(compilation, "sel.QShadowView"))
                .contains("Row SELECTED_FIELDS = row.scoped(BUYER_TABLE);")
                .contains("QShadowView.SELECTED_FIELDS.selectedIn(row)");
        Class<?> qModel = loader.loadClass("sel.QShadowView");

        Object view = mapper(qModel).map(row(qModel, "ID", 1L));

        assertThat(fields(call(view, "selected"))).containsExactly(constant(qModel, "ID"));
    }

    // ---- AC-DIAG-10

    @Test
    void ac_diag_10_a_type_other_than_the_model_s_own_select_set_is_mq3020() {
        Compilation wrong = compile(CUSTOMER_ENTITY, ORDER_ENTITY, CUSTOMER_VIEW,
                source("sel.OtherView", HEAD + """
                        @QueryModel(root = OrderEntity.class)
                        public record OtherView(@PrimaryKey Long id, @Selected SelectSet<CustomerView> other) {}
                        """),
                source("sel.RawView", HEAD + """
                        @QueryModel(root = OrderEntity.class)
                        public record RawView(@PrimaryKey Long id, @Selected SelectSet raw) {}
                        """),
                source("sel.TextView", HEAD + """
                        @QueryModel(root = OrderEntity.class)
                        public record TextView(@PrimaryKey Long id, @Selected String text) {}
                        """));

        assertThat(wrong).hadErrorCount(3);
        assertThat(errors(wrong)).containsExactlyInAnyOrder(
                "MQ3020: OtherView.other: @Selected field is SelectSet<CustomerView>, not SelectSet<OtherView>",
                "MQ3020: RawView.raw: @Selected field is SelectSet, not SelectSet<RawView>",
                "MQ3020: TextView.text: @Selected field is String, not SelectSet<TextView>");
    }

    @Test
    void ac_diag_10_a_second_selected_field_is_mq3020() {
        Compilation two = compile(CUSTOMER_ENTITY, ORDER_ENTITY, source("sel.TwoView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record TwoView(
                        @PrimaryKey Long id,
                        @Selected SelectSet<TwoView> first,
                        @Selected SelectSet<TwoView> second) {}
                """));

        assertThat(errors(two)).containsExactly(
                "MQ3020: TwoView.second: a model has one @Selected field; remove this one");
    }

    @Test
    void ac_diag_10_selected_beside_another_field_annotation_is_mq3021_on_the_field() {
        var processor = new ProcessorHarness.Recording();

        Compilation combined = processor.compile(CUSTOMER_ENTITY, ORDER_ENTITY, source("sel.MixedView", HEAD + """
                @QueryModel(root = OrderEntity.class)
                public record MixedView(
                        @PrimaryKey Long id,
                        @Selected @Transient SelectSet<MixedView> selected) {}
                """));

        assertThat(errors(combined)).containsExactly("MQ3021: MixedView.selected: @Selected can't be combined "
                + "with @Transient");
        assertThat(processor.messages()).singleElement().satisfies(message ->
                assertThat(message.element().getSimpleName()).hasToString("selected"));
    }

    @Test
    void ac_diag_10_an_update_model_refuses_selected_with_mq3302() {
        Compilation update = compile(CUSTOMER_ENTITY, ORDER_ENTITY, source("sel.OrderPatch", HEAD + """
                @UpdateModel(root = OrderEntity.class)
                public record OrderPatch(
                        @PrimaryKey Long id, String status, @Selected SelectSet<OrderPatch> selected) {}
                """));

        assertThat(errors(update)).containsExactly("MQ3302: OrderPatch.selected: @Selected isn't allowed on "
                + "@UpdateModel; an update reads no row into the model");
    }

    @Test
    void ac_diag_10_an_insert_model_refuses_selected_with_mq3502() {
        Compilation insert = compile(CUSTOMER_ENTITY, ORDER_ENTITY, source("sel.NewOrder", HEAD + """
                @InsertModel(root = OrderEntity.class)
                public record NewOrder(
                        @PrimaryKey Long id, String status, @Selected SelectSet<NewOrder> selected) {}
                """));

        assertThat(errors(insert)).containsExactly("MQ3502: NewOrder.selected: @Selected isn't allowed on "
                + "@InsertModel; an insert reads no row of its root");
    }

    // ---- helpers

    /** The generated mapper of {@code qModel}. */
    private static RowMapper<?> mapper(Class<?> qModel) throws ReflectiveOperationException {
        return (RowMapper<?>) qModel.getField("MAPPER").get(null);
    }

    /** The public constant of {@code qModel} named {@code name}. */
    private static SelectField<?, ?> constant(Class<?> qModel, String name) throws ReflectiveOperationException {
        return (SelectField<?, ?>) qModel.getField(name).get(null);
    }

    /**
     * A row of a real {@link RowSelection} that selects the constants of {@code qModel} named in {@code pairs}, name
     * then value, each in order. The selection aliases its distinct columns {@code c0}, {@code c1}, ... in that
     * order, which is how the tuple behind the row finds each value.
     */
    private static Row row(Class<?> qModel, Object... pairs) throws ReflectiveOperationException {
        var selected = new ArrayList<SelectField<?, ?>>();
        var values = new ArrayList<Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            selected.add(constant(qModel, (String) pairs[i]));
            values.add(pairs[i + 1]);
        }
        Tuple tuple = (Tuple) Proxy.newProxyInstance(Tuple.class.getClassLoader(), new Class<?>[] {Tuple.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("get") && args.length == 1 && args[0] instanceof String alias) {
                        return values.get(Integer.parseInt(alias.substring(1)));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return RowSelection.of(selected).row(tuple);
    }

    /** The fields of {@code set}, which is a {@code SelectSet}. */
    private static List<SelectField<?, ?>> fields(Object set) {
        return ((SelectSet<?>) set).fields().stream().<SelectField<?, ?>>map(field -> field).toList();
    }

    /** The record component or accessor {@code name} of {@code model}. */
    private static Object call(Object model, String name) throws ReflectiveOperationException {
        return model.getClass().getMethod(name).invoke(model);
    }

    /** The declared field {@code name} of {@code model}, read directly: a class model has no getters for most. */
    private static Object read(Object model, String name) throws ReflectiveOperationException {
        Field declared = model.getClass().getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(model);
    }
}
