package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** {@code @QueryModel(generateInserts = true)} ({@code processor/30} R-PROC-26, {@code processor/31} R-GEN-34). */
class GenerateInsertsTest {

    private static final String MQ3505_MESSAGE = "generateInserts needs an ungrouped model with at least one root "
            + "column it can write";

    private static final JavaFileObject CUSTOMER_ENTITY = source("gi.CustomerEntity", """
            package gi;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
            }
            """);

    private static final JavaFileObject NOTE_ENTITY = source("gi.NoteEntity", """
            package gi;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class NoteEntity {
                @Id
                Long id;
                Long ticketId;
                String text;
            }
            """);

    /** A generated id, a version, a read-only column and two to-ones, which a query model's inserts leave out. */
    private static final JavaFileObject TICKET_ENTITY = source("gi.TicketEntity", """
            package gi;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.GeneratedValue;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;
            import jakarta.persistence.Version;
            import java.math.BigDecimal;

            @Entity
            public class TicketEntity {
                @Id
                @GeneratedValue
                Long id;
                String code;
                String title;
                BigDecimal amount;
                @Version
                Long version;
                @Column(insertable = false)
                String closedBy;
                @ManyToOne
                CustomerEntity customer;
                @ManyToOne
                CustomerEntity assignee;
            }
            """);

    private static final JavaFileObject CUSTOMER_REF = source("gi.CustomerRef", """
            package gi;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;

            @QueryModel(root = CustomerEntity.class)
            public record CustomerRef(@PrimaryKey Long id, String name) {}
            """);

    private static final JavaFileObject NOTE_VIEW = source("gi.NoteView", """
            package gi;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;

            @QueryModel(root = NoteEntity.class)
            public record NoteView(@PrimaryKey Long id, Long ticketId, String text) {}
            """);

    private static final JavaFileObject DOUBLED = source("gi.Doubled", """
            package gi;

            import com.rey.modelquery.core.ColumnField;
            import com.rey.modelquery.core.Expr;
            import com.rey.modelquery.core.ExpressionDefinition;
            import com.rey.modelquery.core.ExpressionField;
            import com.rey.modelquery.core.TableField;
            import java.math.BigDecimal;

            public final class Doubled implements ExpressionDefinition<TicketView, BigDecimal> {
                @Override
                public ExpressionField<TicketView, BigDecimal> expression() {
                    return Expr.times(ColumnField.of(TicketView.class, TableField.root(TicketEntity.class), "amount",
                            BigDecimal.class), BigDecimal.valueOf(2));
                }
            }
            """);

    /** Every kind of field R-PROC-26 leaves out, beside the two root columns it writes. */
    private static final JavaFileObject TICKET_VIEW = source("gi.TicketView", """
            package gi;

            import com.rey.modelquery.annotations.Child;
            import com.rey.modelquery.annotations.Computed;
            import com.rey.modelquery.annotations.FilterColumn;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.annotations.Selected;
            import com.rey.modelquery.annotations.Transient;
            import com.rey.modelquery.core.SelectSet;
            import java.math.BigDecimal;
            import java.util.List;
            import java.util.Optional;

            @QueryModel(root = TicketEntity.class, generateInserts = true)
            @FilterColumn(name = "ASSIGNEE_NAME", path = "assignee.name")
            public record TicketView(
                    @PrimaryKey Long id,
                    String code,
                    Long version,
                    String closedBy,
                    CustomerEntity assignee,
                    @Join Optional<CustomerRef> customer,
                    @Child(foreignKey = "ticketId") List<NoteView> notes,
                    @Computed(Doubled.class) BigDecimal doubled,
                    @Transient String draft,
                    @Selected SelectSet<TicketView> selected,
                    String title) {}
            """);

    /** A root whose id is generated and whose unique code a query model keys on. */
    private static final JavaFileObject TAG_ENTITY = source("gi.TagEntity", """
            package gi;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.GeneratedValue;
            import jakarta.persistence.Id;

            @Entity
            public class TagEntity {
                @Id
                @GeneratedValue
                Long id;
                @Column(unique = true)
                String code;
                String label;
            }
            """);

    private static final JavaFileObject STOCK_ID = source("gi.StockId", """
            package gi;

            import java.io.Serializable;

            public class StockId implements Serializable {
                Long warehouseId;
                Long productId;
            }
            """);

    private static final JavaFileObject STOCK_ENTITY = source("gi.StockEntity", """
            package gi;

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

    /** A root whose id is assigned, with a unique reference beside it. */
    private static final JavaFileObject ARCHIVE_ENTITY = source("gi.ArchiveEntity", """
            package gi;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class ArchiveEntity {
                @Id
                Long orderId;
                @Column(unique = true)
                String ref;
                String status;
            }
            """);

    private static JavaFileObject model(String name, String annotation, String components) {
        return source("gi." + name, """
                package gi;

                import com.rey.modelquery.annotations.Aggregate;
                import com.rey.modelquery.annotations.AggregateFunction;
                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                %s
                public record %s(%s) {}
                """.formatted(annotation, name, components));
    }

    // ---- AC-PROC-20

    private static final JavaFileObject CUSTOMER_REF_CONVERTER = source("gi.CustomerRefConverter", """
            package gi;

            import com.rey.modelquery.core.ColumnConverter;

            public final class CustomerRefConverter implements ColumnConverter<String, CustomerEntity> {
                public static final CustomerRefConverter INSTANCE = new CustomerRefConverter();

                @Override
                public String toModel(CustomerEntity attribute) {
                    return attribute == null ? null : attribute.toString();
                }

                @Override
                public CustomerEntity toAttribute(String model) {
                    return null;
                }
            }
            """);

    private static JavaFileObject toOneModel(String annotation) {
        return model("TicketRow", annotation, "@PrimaryKey Long id, String title, CustomerEntity customer, "
                + "@Column(attribute = \"assignee\", converter = CustomerRefConverter.class) String assignee");
    }

    /** The Javadoc line that names {@code field} as a to-one the writes of {@code model} leave out. */
    private static String toOneLeftOut(String field, String model) {
        return "<li>{@code " + field + "}: a to-one; only an {@code @" + model + "} writes a foreign key</li>";
    }

    @Test
    void ac_proc_20_generate_changes_leaves_out_a_whole_entity_and_a_converted_to_one_and_names_them() {
        Compilation compilation = compile(CUSTOMER_ENTITY, TICKET_ENTITY, CUSTOMER_REF_CONVERTER,
                toOneModel("@QueryModel(root = TicketEntity.class, generateChanges = true)"));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(errors(compilation)).isEmpty();
        assertThat(generated(compilation, "gi.TicketRowChanges"))
                .contains("public TicketRowChanges title(String value)")
                .doesNotContain("customer(").doesNotContain("assignee(").doesNotContain("Customer(")
                .doesNotContain("Assignee(")
                .contains(toOneLeftOut("customer", "UpdateModel")).contains(toOneLeftOut("assignee", "UpdateModel"));
    }

    @Test
    void ac_proc_20_generate_changes_and_generate_inserts_leave_out_the_same_to_ones() {
        Compilation compilation = compile(CUSTOMER_ENTITY, TICKET_ENTITY, CUSTOMER_REF_CONVERTER, toOneModel(
                "@QueryModel(root = TicketEntity.class, generateChanges = true, generateInserts = true)"));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(errors(compilation)).isEmpty();
        assertThat(generated(compilation, "gi.QTicketRow"))
                .contains(toOneLeftOut("customer", "InsertModel")).contains(toOneLeftOut("assignee", "InsertModel"));
        assertThat(generated(compilation, "gi.TicketRowChanges"))
                .contains(toOneLeftOut("customer", "UpdateModel")).contains(toOneLeftOut("assignee", "UpdateModel"));
    }

    // ---- AC-PROC-19

    @Test
    void ac_proc_19_root_columns_are_written_and_every_left_out_field_is_named_in_the_javadoc() {
        Compilation compilation = compile(CUSTOMER_ENTITY, NOTE_ENTITY, TICKET_ENTITY, CUSTOMER_REF, NOTE_VIEW,
                DOUBLED, TICKET_VIEW);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        // The whole-entity column warns as on any query model; nothing is said of what the inserts leave out.
        assertThat(compilation.diagnostics().stream().filter(d -> d.getKind() != Diagnostic.Kind.NOTE)
                .map(d -> d.getMessage(Locale.ROOT)))
                .singleElement().asString().startsWith("MQ3016: TicketView.assignee: ");
        assertThat(generated(compilation, "gi.QTicketView")).contains("""
                    /**
                     * The root columns {@code insert}, {@code insertFrom} and {@code persist} write.
                     * <p>Left out:
                     * <ul>
                     * <li>{@code id}: the id, which is generated</li>
                     * <li>{@code version}: the {@code @Version}, which the provider writes</li>
                     * <li>{@code closedBy}: {@code @Column(insertable = false)}</li>
                     * <li>{@code assignee}: a to-one; only an {@code @InsertModel} writes a foreign key</li>
                     * <li>{@code customer}: {@code @Join}, columns of another table</li>
                     * <li>{@code notes}: {@code @Child}, rows of another model</li>
                     * <li>{@code doubled}: {@code @Computed}, an expression</li>
                     * <li>{@code draft}: {@code @Transient}, no column</li>
                     * <li>{@code selected}: {@code @Selected}, the columns the row selected</li>
                     * <li>{@code @FilterColumn(ASSIGNEE_NAME)}: filter-only, no field of the model</li>
                     * </ul>
                     */
                    @Incubating
                    public static final InsertColumns<TicketView, TicketEntity> INSERT_COLUMNS = \
                InsertColumns.<TicketView, TicketEntity>of(ROOT)
                            .add(CODE, TicketView::code)
                            .add(TITLE, TicketView::title);
                """);
        assertThat(generatedFlat(compilation, "gi.QTicketView"))
                .contains("@Incubating public static <S> ModelInsert.SelectStart<TicketEntity, TicketView> "
                        + "insertFrom( TableField<S, S> sourceRoot) {")
                .contains("@Incubating public static ValuesInsert.Rows<TicketEntity, Long, TicketView> insert( "
                        + "List<? extends TicketView> rows) { return ValuesInsert.builder(INSERT_COLUMNS, Long.class, "
                        + "rows); }")
                .contains("@Incubating public static ModelPersist<TicketEntity, Long, TicketView> persist("
                        + "TicketView row) { return ModelPersist.of(INSERT_COLUMNS, Long.class, row); }")
                // Still a query model
                .contains("public static ModelQuery.Builder<TicketEntity, Long, TicketView> query()");
    }

    @Test
    void ac_proc_19_generate_changes_and_generate_inserts_combine() {
        Compilation compilation = compile(ARCHIVE_ENTITY, model("ArchiveView",
                "@QueryModel(root = ArchiveEntity.class, generateChanges = true, generateInserts = true)",
                "@PrimaryKey Long orderId, String ref, String status"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "gi.QArchiveView"))
                .contains(".addKey(ORDER_ID, ArchiveView::orderId) .add(REF, ArchiveView::ref) "
                        + ".add(STATUS, ArchiveView::status);")
                .contains("public static ArchiveViewChanges changes()")
                .contains(" update( Changes<ArchiveView> changes)")
                .contains(" delete()")
                .contains(" insert( List<? extends ArchiveView> rows)")
                .contains(" persist(ArchiveView row)");
        assertThat(compilation.generatedSourceFile("gi.ArchiveViewChanges")).isPresent();
    }

    @Test
    void ac_proc_19_without_the_flag_a_query_model_generates_no_insert_members() {
        Compilation compilation = compile(ARCHIVE_ENTITY, model("ArchiveView",
                "@QueryModel(root = ArchiveEntity.class)",
                "@PrimaryKey Long orderId, @Column(attribute = \"status\") String insertColumns"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "gi.QArchiveView"))
                .doesNotContain("InsertColumns<").doesNotContain(" persist(").doesNotContain(" insert(");
    }

    // ---- AC-GEN-26

    @Test
    void ac_gen_26_an_id_class_is_added_with_add_key_and_types_insert_and_persist_while_key_stays_composite()
            throws ReflectiveOperationException {
        Compilation compilation = compile(STOCK_ID, STOCK_ENTITY, model("StockView",
                "@QueryModel(root = StockEntity.class, generateInserts = true)",
                "Integer quantity, @PrimaryKey Long productId, @PrimaryKey Long warehouseId"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "gi.QStockView"))
                .contains(".add(QUANTITY, StockView::quantity) .addKey(PRODUCT_ID, StockView::productId) "
                        + ".addKey(WAREHOUSE_ID, StockView::warehouseId);")
                .contains("public static final PrimaryKey<StockView, List<Object>> KEY")
                .contains("public static ValuesInsert.Rows<StockEntity, StockId, StockView> insert(")
                .contains("public static ModelPersist<StockEntity, StockId, StockView> persist(StockView row) { "
                        + "return ModelPersist.of(INSERT_COLUMNS, StockId.class, row); }");
        InsertColumns<?, ?> columns = insertColumns(compilation, "gi.QStockView");
        assertThat(columns.keyColumns()).extracting(ColumnField::name).containsExactly("productId", "warehouseId");
        assertThat(columns.columns()).extracting(ColumnField::name)
                .containsExactly("quantity", "productId", "warehouseId");
    }

    @Test
    void ac_gen_26_a_non_id_primary_key_over_a_generated_id_is_added_with_add() throws ReflectiveOperationException {
        Compilation compilation = compile(TAG_ENTITY, model("TagView",
                "@QueryModel(root = TagEntity.class, generateInserts = true)",
                "@PrimaryKey String code, String label"));

        assertThat(compilation).succeededWithoutWarnings();
        String flat = generatedFlat(compilation, "gi.QTagView");
        assertThat(flat)
                .contains("/** * The root columns {@code insert}, {@code insertFrom} and {@code persist} write. */ "
                        + "@Incubating public static final InsertColumns<TagView, TagEntity> INSERT_COLUMNS = "
                        + "InsertColumns.<TagView, TagEntity>of(ROOT) .add(CODE, TagView::code) "
                        + ".add(LABEL, TagView::label);")
                .contains("public static final PrimaryKey<TagView, String> KEY")
                .contains("public static ValuesInsert.Rows<TagEntity, Long, TagView> insert(")
                .contains("public static ModelPersist<TagEntity, Long, TagView> persist(TagView row)");
        assertThat(insertColumns(compilation, "gi.QTagView").keyColumns()).isEmpty();
    }

    // ---- AC-DIAG-12

    @Test
    void ac_diag_12_mq3505_refuses_an_aggregate_a_group_by_and_a_single_group_model() {
        Compilation compilation = compile(TAG_ENTITY,
                model("TagTotals", "@QueryModel(root = TagEntity.class, generateInserts = true)",
                        "@GroupBy String label, @Aggregate(fn = AggregateFunction.COUNT) Long tags"),
                model("TagLabels", "@QueryModel(root = TagEntity.class, generateInserts = true)",
                        "@PrimaryKey String code, @GroupBy String label"),
                model("TagCount", "@QueryModel(root = TagEntity.class, singleGroup = true, generateInserts = true)",
                        "@Aggregate(fn = AggregateFunction.COUNT) Long tags"));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                "MQ3505: TagTotals: " + MQ3505_MESSAGE,
                "MQ3505: TagLabels: " + MQ3505_MESSAGE,
                "MQ3505: TagCount: " + MQ3505_MESSAGE);
    }

    @Test
    void ac_diag_12_mq3505_refuses_a_model_with_no_root_column_it_can_write() {
        Compilation compilation = compile(TAG_ENTITY, model("TagIds",
                "@QueryModel(root = TagEntity.class, generateInserts = true)", "@PrimaryKey Long id"));

        assertThat(errors(compilation)).containsExactly("MQ3505: TagIds: " + MQ3505_MESSAGE);
    }

    @Test
    void ac_diag_12_mq3501_fires_when_the_primary_key_does_not_name_an_assigned_id() {
        Compilation compilation = compile(ARCHIVE_ENTITY, STOCK_ID, STOCK_ENTITY,
                model("ArchiveView", "@QueryModel(root = ArchiveEntity.class, generateInserts = true)",
                        "@PrimaryKey String ref, String status"),
                model("StockView", "@QueryModel(root = StockEntity.class, generateInserts = true)",
                        "@PrimaryKey Long warehouseId, Integer quantity"));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                "MQ3501: ArchiveView: ArchiveEntity's id 'orderId' has no @GeneratedValue or generator annotation; "
                        + "generateInserts writes it, so map it in the model",
                "MQ3501: StockView: StockEntity's id 'productId', 'warehouseId' has no @GeneratedValue or generator "
                        + "annotation; generateInserts writes it, so map it in the model");
    }

    @Test
    void ac_diag_12_mq3501_fires_when_the_assigned_id_its_primary_key_names_is_left_out() {
        JavaFileObject readOnlyId = source("gi.LedgerEntity", """
                package gi;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                public class LedgerEntity {
                    @Id
                    @Column(insertable = false)
                    Long entryId;
                    String memo;
                }
                """);
        Compilation compilation = compile(readOnlyId, model("LedgerView",
                "@QueryModel(root = LedgerEntity.class, generateInserts = true)",
                "@PrimaryKey Long entryId, String memo"));

        assertThat(errors(compilation)).containsExactly(
                "MQ3501: LedgerView: LedgerEntity's id 'entryId' has no @GeneratedValue or generator annotation; "
                        + "generateInserts writes it, so map it in the model");
    }

    @Test
    void ac_diag_12_mq3501_does_not_fire_when_an_unannotated_column_writes_the_assigned_id() {
        Compilation compilation = compile(ARCHIVE_ENTITY, model("ArchiveView",
                "@QueryModel(root = ArchiveEntity.class, generateInserts = true)",
                "@PrimaryKey String ref, Long orderId, String status"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "gi.QArchiveView"))
                .contains(".add(REF, ArchiveView::ref) .addKey(ORDER_ID, ArchiveView::orderId)")
                .contains("ModelPersist<ArchiveEntity, Long, ArchiveView> persist(")
                .contains("PrimaryKey<ArchiveView, String>");
    }

    @Test
    void ac_diag_12_mq3501_does_not_fire_for_a_non_id_primary_key_over_a_generated_id() {
        Compilation compilation = compile(TAG_ENTITY, model("TagView",
                "@QueryModel(root = TagEntity.class, generateInserts = true)", "@PrimaryKey String code, Long id"));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "gi.QTagView")).contains(".add(CODE, TagView::code);");
    }

    @Test
    void ac_diag_12_mq3504_warns_and_the_qmodel_is_generated() {
        Compilation compilation = compile(source("gi.LegacyEntity", """
                        package gi;

                        import jakarta.persistence.Entity;

                        @Entity
                        public class LegacyEntity {
                            Long id;
                            String name;
                        }
                        """),
                model("LegacyView", "@QueryModel(root = LegacyEntity.class, generateInserts = true)",
                        "@PrimaryKey Long id, String name"));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(compilation.warnings()).extracting(warning -> warning.getMessage(Locale.ROOT)).containsExactly(
                "MQ3504: LegacyView: LegacyEntity has no id type the processor can see; insert(rows) and "
                        + "persist(row) return its keys as Object");
        assertThat(generatedFlat(compilation, "gi.QLegacyView"))
                .contains(".add(ID, LegacyView::id) .add(NAME, LegacyView::name);")
                .contains("public static ModelPersist<LegacyEntity, Object, LegacyView> persist(");
    }

    @Test
    void ac_diag_12_insert_columns_is_a_reserved_constant() {
        Compilation compilation = compile(ARCHIVE_ENTITY, model("ArchiveView",
                "@QueryModel(root = ArchiveEntity.class, generateInserts = true)",
                "@PrimaryKey Long orderId, @Column(attribute = \"status\") String insertColumns"));

        assertThat(errors(compilation)).containsExactly("MQ3015: ArchiveView.insertColumns: constant INSERT_COLUMNS "
                + "is reserved by the generated class; rename the field");
    }

    private static InsertColumns<?, ?> insertColumns(Compilation compilation, String qmodel)
            throws ReflectiveOperationException {
        return (InsertColumns<?, ?>) classes(compilation).loadClass(qmodel).getField("INSERT_COLUMNS").get(null);
    }
}
