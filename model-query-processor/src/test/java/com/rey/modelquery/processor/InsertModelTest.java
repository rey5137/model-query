package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** Insert models and their generated QModel ({@code processor/30} §8, {@code processor/31} §7, D-117). */
class InsertModelTest {

    @Test
    void ac_gen_13_record_insert_model_over_a_generated_id_matches_its_golden_file() {
        Compilation compilation = compile(InsertModelSources.NEW_ORDER_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "ins.QNewOrder")).isEqualTo(resource("golden/QNewOrder.java"));
    }

    @Test
    void ac_gen_13_class_insert_model_over_an_assigned_id_matches_its_golden_file() {
        Compilation compilation = compile(InsertModelSources.ORDER_ARCHIVE_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "ins.QOrderArchiveRow"))
                .isEqualTo(resource("golden/QOrderArchiveRow.java"));
    }

    @Test
    void ac_gen_13_the_insert_model_file_originates_from_the_model_alone() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(InsertModelSources.NEW_ORDER_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(processor.originating()).containsOnlyKeys("ins.QNewOrder");
        assertThat(processor.originating().get("ins.QNewOrder")).singleElement().satisfies(element -> {
            assertThat(element.getKind()).isEqualTo(ElementKind.RECORD);
            assertThat(element).hasToString("ins.NewOrder");
        });
    }

    @Test
    void ac_gen_14_key_type_is_the_id_class_or_a_mapped_superclass_variable_resolved_on_the_root() {
        Compilation compilation = compile(InsertModelSources.STOCK_ENTITY, InsertModelSources.STOCK_ID,
                InsertModelSources.STOCK_ROW, InsertModelSources.BASE_ENTITY, InsertModelSources.TAG_ENTITY,
                InsertModelSources.TAG_ROW);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "ins.QStockRow"))
                .contains(".addKey(WAREHOUSE_ID, StockRow::warehouseId) .addKey(PRODUCT_ID, StockRow::productId) "
                        + ".add(QUANTITY, StockRow::quantity);")
                .contains("public static ValuesInsert.Rows<StockEntity, StockId, StockRow> insert( "
                        + "List<? extends StockRow> rows) { return ValuesInsert.builder(INSERT_COLUMNS, "
                        + "StockId.class, rows); }")
                .contains("public static ModelPersist<StockEntity, StockId, StockRow> persist(StockRow row) { "
                        + "return ModelPersist.of(INSERT_COLUMNS, StockId.class, row); }");
        assertThat(generatedFlat(compilation, "ins.QTagRow"))
                .contains("public static ValuesInsert.Rows<TagEntity, String, TagRow> insert(")
                .contains("public static ModelPersist<TagEntity, String, TagRow> persist(TagRow row)");
    }

    @Test
    void ac_gen_14_a_primitive_id_is_boxed() {
        Compilation compilation = compile(source("ins.CounterEntity", """
                        package ins;

                        import jakarta.persistence.Entity;
                        import jakarta.persistence.Id;

                        @Entity
                        public class CounterEntity {
                            @Id
                            long id;
                            String name;
                        }
                        """),
                source("ins.CounterRow", """
                        package ins;

                        import com.rey.modelquery.annotations.InsertModel;
                        import com.rey.modelquery.annotations.PrimaryKey;

                        @InsertModel(root = CounterEntity.class)
                        public record CounterRow(@PrimaryKey long id, String name) {}
                        """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "ins.QCounterRow"))
                .contains(".addKey(ID, CounterRow::id)")
                .contains("public static ValuesInsert.Rows<CounterEntity, Long, CounterRow> insert(")
                .contains("ValuesInsert.builder(INSERT_COLUMNS, Long.class, rows)")
                .contains("public static ModelPersist<CounterEntity, Long, CounterRow> persist(CounterRow row) { "
                        + "return ModelPersist.of(INSERT_COLUMNS, Long.class, row); }");
    }

    @Test
    void ac_gen_14_without_a_visible_id_keys_are_typed_object_with_a_warning() {
        Compilation compilation = compile(source("ins.LegacyEntity", """
                        package ins;

                        import jakarta.persistence.Entity;

                        @Entity
                        public class LegacyEntity {
                            Long id;
                            String name;
                        }
                        """),
                source("ins.LegacyRow", """
                        package ins;

                        import com.rey.modelquery.annotations.InsertModel;

                        @InsertModel(root = LegacyEntity.class)
                        public record LegacyRow(String name) {}
                        """));

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("MQ3504: LegacyRow: LegacyEntity has no id type the processor "
                + "can see; insert(rows) and persist(row) return its keys as Object");
        assertThat(generatedFlat(compilation, "ins.QLegacyRow"))
                .contains("public static ValuesInsert.Rows<LegacyEntity, Object, LegacyRow> insert(")
                .contains("ValuesInsert.builder(INSERT_COLUMNS, Object.class, rows)")
                .contains("public static ModelPersist<LegacyEntity, Object, LegacyRow> persist(LegacyRow row)");
    }

    /**
     * The generated builders link against the real core: an insert-values, a persist and an insert-select over a
     * query model's root compile with their key type, and build.
     */
    @Test
    void ac_gen_13_generated_builders_compile_and_build_against_the_core() throws Exception {
        JavaFileObject usage = source("ins.Usage", """
                package ins;

                import com.rey.modelquery.core.ModelInsert;
                import com.rey.modelquery.core.ModelPersist;
                import com.rey.modelquery.core.ValuesInsert;
                import java.math.BigDecimal;
                import java.util.List;
import java.util.stream.Stream;

                public final class Usage {
                    public static List<Object> run() {
                        List<NewOrder> rows = List.of(
                                new NewOrder("A-1", OrderStatus.NEW, BigDecimal.ONE, 7L, "Oslo"),
                                new NewOrder("A-2", OrderStatus.SHIPPED, BigDecimal.TEN, 8L, null));
                        ValuesInsert<OrderEntity, Long, NewOrder> values = QNewOrder.insert(rows).build();
                        ModelPersist<OrderEntity, Long, NewOrder> persist = QNewOrder.persist(rows.get(0));
                        ModelInsert<OrderArchiveEntity, OrderArchiveRow> select =
                                QOrderArchiveRow.insertFrom(QOrderView.ROOT)
                                        .map(QOrderArchiveRow.ORDER_ID, QOrderView.ID)
                                        .map(QOrderArchiveRow.STATUS, QOrderView.STATUS)
                                        .map(QOrderArchiveRow.PAID, QOrderView.PAID)
                                        .all()
                                        .build();
                        ValuesInsert<OrderArchiveEntity, Long, OrderArchiveRow> archived = QOrderArchiveRow
                                .insert(List.of(new OrderArchiveRow(3L, "NEW", true)))
                                .build();
                        return List.of(values.keyType(), persist.keyType(), select.rootEntity(),
                                archived.keyType(), QNewOrder.INSERT_COLUMNS.columns(),
                                QOrderArchiveRow.INSERT_COLUMNS.keyColumns());
                    }
                }
                """);
        Compilation compilation = compile(InsertModelSources.ADDRESS, InsertModelSources.CUSTOMER_ENTITY,
                InsertModelSources.ORDER_ENTITY, InsertModelSources.ORDER_STATUS, InsertModelSources.NEW_ORDER,
                InsertModelSources.ORDER_ARCHIVE_ENTITY, InsertModelSources.ORDER_ARCHIVE_ROW,
                InsertModelSources.ORDER_VIEW, usage);
        assertThat(compilation).succeededWithoutWarnings();
        ClassLoader loader = classes(compilation);

        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) loader.loadClass("ins.Usage").getMethod("run").invoke(null);

        Class<?> newOrder = loader.loadClass("ins.QNewOrder");
        Class<?> archive = loader.loadClass("ins.QOrderArchiveRow");
        assertThat(result.subList(0, 4)).containsExactly(
                Long.class, Long.class, loader.loadClass("ins.OrderArchiveEntity"), Long.class);
        assertThat((List<Object>) result.get(4)).containsExactly(newOrder.getField("EXTERNAL_REF").get(null),
                newOrder.getField("STATUS").get(null), newOrder.getField("TOTAL").get(null),
                newOrder.getField("CUSTOMER_ID").get(null), newOrder.getField("CITY").get(null));
        assertThat((List<Object>) result.get(5)).containsExactly(archive.getField("ORDER_ID").get(null));
    }

    @Test
    void ac_gen_13_insert_columns_and_the_three_builders_are_incubating() throws Exception {
        Compilation compilation = compile(InsertModelSources.NEW_ORDER_SOURCES);
        assertThat(compilation).succeededWithoutWarnings();
        // @Incubating is CLASS-retained, so the generated source is what carries it.
        String generated = generatedFlat(compilation, "ins.QNewOrder");

        assertThat(generated)
                .contains("@Incubating public static final InsertColumns<NewOrder, OrderEntity> INSERT_COLUMNS")
                .contains("@Incubating public static <S> ModelInsert.SelectStart<OrderEntity, NewOrder> insertFrom(")
                .contains("@Incubating public static ValuesInsert.Rows<OrderEntity, Long, NewOrder> insert(")
                .contains("@Incubating public static ModelPersist<OrderEntity, Long, NewOrder> persist(")
                .doesNotContain("MAPPER", "query()", "KEY =", "update(", "delete()");
        Class<?> type = classes(compilation).loadClass("ins.QNewOrder");
        assertThat(Stream.of(type.getDeclaredMethods()).filter(method -> !method.isSynthetic()).map(Method::getName))
                .containsExactlyInAnyOrder("insertFrom", "insert", "persist");
    }
}
