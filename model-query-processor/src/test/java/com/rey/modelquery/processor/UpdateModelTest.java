package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectField;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ElementKind;
import org.junit.jupiter.api.Test;

/** Update models, change sets and the write methods of a QModel ({@code processor/31} §6, {@code api/14} §2). */
class UpdateModelTest {

    @Test
    void ac_gen_10_record_update_model_with_a_converter_and_a_to_one_by_id_matches_its_golden_files() {
        Compilation compilation = compile(UpdateModelSources.ORDER_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "patch.QOrderPatch")).isEqualTo(resource("golden/QOrderPatch.java"));
        assertThat(generated(compilation, "patch.OrderPatchChanges"))
                .isEqualTo(resource("golden/OrderPatchChanges.java"));
    }

    @Test
    void ac_gen_10_class_update_model_with_a_composite_key_matches_its_golden_files() {
        Compilation compilation = compile(UpdateModelSources.STOCK_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "patch.QStockPatch")).isEqualTo(resource("golden/QStockPatch.java"));
        assertThat(generated(compilation, "patch.StockPatchChanges"))
                .isEqualTo(resource("golden/StockPatchChanges.java"));
    }

    @Test
    void ac_gen_10_both_files_of_an_update_model_originate_from_the_model_alone() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(UpdateModelSources.ORDER_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(processor.originating()).containsOnlyKeys("patch.QOrderPatch", "patch.OrderPatchChanges");
        for (String file : processor.originating().keySet()) {
            assertThat(processor.originating().get(file)).singleElement().satisfies(element -> {
                assertThat(element.getKind()).isEqualTo(ElementKind.RECORD);
                assertThat(element).hasToString("patch.OrderPatch");
            });
        }
    }

    @Test
    void ac_gen_11_generate_changes_covers_root_non_key_columns_only() {
        Compilation compilation = compile(UpdateModelSources.ORDER_EDIT_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "patch.QOrderEdit"))
                .contains("public static OrderEditChanges changes() { return new OrderEditChanges(); }")
                .contains("public static ModelUpdate.Builder<OrderEntity, Long, OrderEdit> update( "
                        + "Changes<OrderEdit> changes) { return ModelUpdate.builder(ROOT).primaryKey(KEY)"
                        + ".set(changes); }")
                .contains("public static ModelDelete.Builder<OrderEntity, Long, OrderEdit> delete() { "
                        + "return ModelDelete.builder(ROOT).primaryKey(KEY); }");
        String changes = generatedFlat(compilation, "patch.OrderEditChanges");
        assertThat(changes)
                .contains("private OrderStatus status; private String note; private String city; public")
                .contains("public static OrderEditChanges from(OrderEdit model, ColumnSet<OrderEdit> columns)")
                .contains("changes.status(model.status());")
                .doesNotContain("QOrderEdit.ID", "CUSTOMER", "Optional");
        assertThat(generatedFlat(compilation, "patch.PaidViewChanges"))
                .contains("changes.paid(model.isPaid());")
                .contains("changes.note(model.getNote());")
                .contains("public PaidViewChanges paid(Boolean value)");
        // A query model asks for a change set: without generateChanges it has neither the file nor the methods.
        assertThat(compilation.generatedSourceFile("patch.CustomerNameChanges")).isEmpty();
        assertThat(generatedFlat(compilation, "patch.QCustomerName"))
                .doesNotContain("changes()")
                .doesNotContain("update(")
                .contains("delete()");
    }

    @Test
    void ac_gen_11_a_query_model_gets_delete_exactly_when_its_key_is_the_root_entity_id() {
        Compilation compilation = compile(
                UpdateModelSources.ADDRESS, UpdateModelSources.CUSTOMER_ENTITY, UpdateModelSources.ORDER_ENTITY,
                UpdateModelSources.STOCK_ENTITY, UpdateModelSources.STOCK_ID,
                model("OrderByNumber", "OrderEntity", "@PrimaryKey String orderNo, String note"),
                model("StockRow", "StockEntity", "@PrimaryKey Long warehouseId, @PrimaryKey Long productId"),
                model("HalfStockRow", "StockEntity", "@PrimaryKey Long warehouseId, Integer quantity"),
                source("patch.OrderLineRow", """
                        package patch;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;
                        import com.rey.modelquery.processor.fixture.OrderLineEntity;

                        @QueryModel(root = OrderLineEntity.class)
                        public record OrderLineRow(
                                @PrimaryKey @Column(attribute = "id.orderId") Long orderId,
                                @PrimaryKey @Column(attribute = "id.lineNo") Integer lineNo) {}
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "patch.QOrderByNumber")).doesNotContain("delete()");
        assertThat(generatedFlat(compilation, "patch.QHalfStockRow")).doesNotContain("delete()");
        assertThat(generatedFlat(compilation, "patch.QStockRow"))
                .contains("public static ModelDelete.Builder<StockEntity, List<Object>, StockRow> delete()");
        assertThat(generatedFlat(compilation, "patch.QOrderLineRow"))
                .contains("public static ModelDelete.Builder<OrderLineEntity, List<Object>, OrderLineRow> delete()");
    }

    @Test
    void ac_wrt_02_a_change_set_bound_by_jackson_clears_a_present_null_and_leaves_an_absent_field_alone()
            throws Exception {
        ClassLoader loader = classes(compile(UpdateModelSources.ORDER_PATCH_SOURCES));
        Class<?> changesClass = loader.loadClass("patch.OrderPatchChanges");
        ColumnField<Object, ?, ?> note = constant(loader, "patch.QOrderPatch", "NOTE");
        ColumnField<Object, ?, ?> total = constant(loader, "patch.QOrderPatch", "TOTAL");
        var mapper = new ObjectMapper();

        Changes<Object> cleared = changes(mapper.readValue("{\"note\": null}", changesClass));
        Changes<Object> other = changes(mapper.readValue("{\"total\": 12.5}", changesClass));
        Changes<Object> none = changes(mapper.readValue("{}", changesClass));

        assertThat(cleared.isSet(note)).isTrue();
        assertThat(cleared.assignments()).containsExactly(Assignment.ofNull(note));
        assertThat(other.isSet(note)).isFalse();
        assertThat(other.assignments()).containsExactly(assignment(total, new BigDecimal("12.5")));
        assertThat(none.isEmpty()).isTrue();
        assertThat(none.assignments()).isEmpty();
        // The update copies the change set's assignments: the NULL survives into the definition.
        Object builder = invoke(loader.loadClass("patch.QOrderPatch").getMethod("update", Changes.class), cleared);
        ModelUpdate<?, ?> update = ((ModelUpdate.Builder<?, ?, ?>) builder).all().build();
        assertThat(update.assignments()).isEqualTo(List.of(Assignment.ofNull(note)));
    }

    @Test
    void r_wrt_02_a_change_set_keeps_null_and_not_set_apart_in_declaration_order() throws Exception {
        ClassLoader loader = classes(compile(UpdateModelSources.ORDER_PATCH_SOURCES));
        Class<?> q = loader.loadClass("patch.QOrderPatch");
        ColumnField<Object, ?, ?> status = constant(loader, "patch.QOrderPatch", "STATUS");
        ColumnField<Object, ?, ?> note = constant(loader, "patch.QOrderPatch", "NOTE");
        ColumnField<Object, ?, ?> customerId = constant(loader, "patch.QOrderPatch", "CUSTOMER_ID");
        ColumnField<Object, ?, ?> id = constant(loader, "patch.QOrderPatch", "ID");
        Object statusValue = loader.loadClass("patch.OrderStatus").getEnumConstants()[1];
        Object changes = invoke(q.getMethod("changes"));
        Class<?> type = changes.getClass();

        invoke(type.getMethod("customerId", Long.class), changes, 5L);
        invoke(type.getMethod("note", String.class), changes, (Object) null);
        invoke(type.getMethod("status", statusValue.getClass()), changes, statusValue);
        Changes<Object> set = changes(changes);

        assertThat(set.isSet(note)).isTrue();
        assertThat(set.isSet(id)).isFalse();
        assertThat(set.assignments()).containsExactly(
                assignment(status, statusValue), Assignment.ofNull(note), assignment(customerId, 5L));
        assertThat(set.unset(status)).isSameAs(set);
        assertThat(set.isSet(status)).isFalse();
        assertThat(invoke(type.getMethod("getStatus"), changes)).isNull();
        assertThat(set.assignments()).containsExactly(Assignment.ofNull(note), assignment(customerId, 5L));
        assertThatThrownBy(() -> set.assignments().clear()).isInstanceOf(UnsupportedOperationException.class);
        set.unset(note);
        set.unset(customerId);
        assertThat(set.isEmpty()).isTrue();
    }

    @Test
    void ac_wrt_03_from_copies_nulls_and_refuses_a_column_the_change_set_does_not_write() throws Exception {
        ClassLoader loader = classes(compile(UpdateModelSources.ORDER_EDIT_SOURCES));
        Class<?> model = loader.loadClass("patch.OrderEdit");
        Class<?> changesClass = loader.loadClass("patch.OrderEditChanges");
        Method from = changesClass.getMethod("from", model, ColumnSet.class);
        ColumnField<Object, ?, ?> status = constant(loader, "patch.QOrderEdit", "STATUS");
        ColumnField<Object, ?, ?> note = constant(loader, "patch.QOrderEdit", "NOTE");
        ColumnField<Object, ?, ?> city = constant(loader, "patch.QOrderEdit", "CITY");
        Object view = model.getConstructors()[0].newInstance(7L, null, "rush", null, Optional.empty());

        Changes<Object> copied = changes(invoke(from, view, ColumnSet.of(status, note, city)));
        Changes<Object> partial = changes(invoke(from, view, ColumnSet.of(note)));

        assertThat(copied.assignments()).containsExactly(
                Assignment.ofNull(status), assignment(note, "rush"), Assignment.ofNull(city));
        assertThat(partial.assignments()).containsExactly(assignment(note, "rush"));
        for (String constant : new String[] {"ID", "CUSTOMER_NAME", "CUSTOMER_COUNTRY"}) {
            SelectField<Object, ?> column = constant(loader, "patch.QOrderEdit", constant);
            assertThatThrownBy(() -> invoke(from, view, ColumnSet.of(note, column)))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1607))
                    .hasMessage("MQ1607: " + column + ": not a column OrderEditChanges writes; a change set "
                            + "writes OrderEdit's root, non-key columns only");
        }
    }

    @Test
    void ac_wrt_03_from_reads_a_class_model_through_its_getters() throws Exception {
        ClassLoader loader = classes(compile(UpdateModelSources.ORDER_EDIT_SOURCES));
        Class<?> model = loader.loadClass("patch.PaidView");
        Object view = model.getConstructor().newInstance();
        invoke(model.getMethod("setPaid", boolean.class), view, true);
        ColumnField<Object, ?, ?> paid = constant(loader, "patch.QPaidView", "PAID");
        ColumnField<Object, ?, ?> note = constant(loader, "patch.QPaidView", "NOTE");

        Changes<Object> copied = changes(invoke(loader.loadClass("patch.PaidViewChanges")
                .getMethod("from", model, ColumnSet.class), view, ColumnSet.of(paid, note)));

        assertThat(copied.assignments()).containsExactly(assignment(paid, true), Assignment.ofNull(note));
    }

    /** A record query model named {@code patch.<name>} over an entity of {@link UpdateModelSources}. */
    private static javax.tools.JavaFileObject model(String name, String root, String components) {
        return source("patch." + name, "package patch;\n"
                + "import com.rey.modelquery.annotations.PrimaryKey;\n"
                + "import com.rey.modelquery.annotations.QueryModel;\n"
                + "@QueryModel(root = " + root + ".class)\n"
                + "public record " + name + "(" + components + ") {}\n");
    }

    /** The column constant {@code name} of the generated class {@code qModel}, typed loosely for the test. */
    @SuppressWarnings("unchecked")
    private static ColumnField<Object, ?, ?> constant(ClassLoader loader, String qModel, String name)
            throws ReflectiveOperationException {
        return (ColumnField<Object, ?, ?>) loader.loadClass(qModel).getField(name).get(null);
    }

    @SuppressWarnings("unchecked")
    private static Changes<Object> changes(Object changes) {
        return (Changes<Object>) changes;
    }

    @SuppressWarnings("unchecked")
    private static Assignment<Object, ?> assignment(ColumnField<Object, ?, ?> column, Object value) {
        return Assignment.of((ColumnField<Object, ?, Object>) column, value);
    }

    /** Calls {@code method}, rethrowing what it threw rather than its reflective wrapper. */
    private static Object invoke(Method method, Object... arguments) throws Exception {
        boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
        Object target = isStatic ? null : arguments[0];
        Object[] rest = isStatic ? arguments : java.util.Arrays.copyOfRange(arguments, 1, arguments.length);
        try {
            return method.invoke(target, rest);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
