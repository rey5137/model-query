package com.rey.modelquery.processor;

import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.testing.compile.Compilation;
import javax.lang.model.element.ElementKind;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

/** The insert-model diagnostics beyond the matrix's one case per code (spec processor/32 §1, R-PROC-23, R-PROC-24). */
class InsertModelDiagnosticsTest {

    private static final String TICKET_ENTITY = """
            package ins;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.JoinColumn;
            import jakarta.persistence.ManyToOne;
            import jakarta.persistence.OneToMany;
            import jakarta.persistence.OneToOne;
            import jakarta.persistence.Version;
            import java.util.List;

            @Entity
            public class TicketEntity {
                @Id
                Long id;
                @Version
                Long version;
                @Column(updatable = false)
                String createdBy;
                @Column(insertable = false)
                String closedBy;
                @ManyToOne
                CustomerEntity customer;
                @ManyToOne
                @JoinColumn(name = "assignee_id", insertable = false, updatable = false)
                CustomerEntity assignee;
                @OneToOne(mappedBy = "ticket")
                OrderEntity order;
                @OneToMany
                List<CustomerEntity> watchers;
            }
            """;

    @Test
    void ac_proc_15_an_insert_model_column_takes_the_write_checks_of_an_update_model() {
        Compilation compilation = compile(InsertModelSources.ADDRESS, InsertModelSources.CUSTOMER_ENTITY,
                InsertModelSources.ORDER_ENTITY, source("ins.TicketEntity", TICKET_ENTITY),
                source("ins.TicketRow", """
                        package ins;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.InsertModel;
                        import com.rey.modelquery.annotations.PrimaryKey;

                        @InsertModel(root = TicketEntity.class)
                        public record TicketRow(
                                @PrimaryKey Long id,
                                Long version,
                                @Column(attribute = "customer.name") String customerName,
                                @Column(attribute = "watchers") String watchers,
                                @Column(attribute = "order") Long orderId,
                                @Column(attribute = "customer") String customerId,
                                String closedBy,
                                @Column(attribute = "assignee") Long assigneeId) {}
                        """));

        assertThat(errors(compilation)).containsExactlyInAnyOrder(
                "MQ3303: TicketRow.version: the @Version attribute is written by the provider, with its seed value",
                "MQ3301: TicketRow.customerName: insert models can only write attributes of TicketEntity; "
                        + "'customer.name' needs a join",
                "MQ3301: TicketRow.watchers: insert models can only write attributes of TicketEntity; 'watchers' "
                        + "is a collection",
                "MQ3304: TicketRow.orderId: TicketEntity.order is the inverse side of a to-one (mappedBy = "
                        + "\"ticket\"); write it from the owning side",
                "MQ3305: TicketRow.customerId: CustomerEntity's id is Long, found String",
                "MQ3304: TicketRow.closedBy: TicketEntity.closedBy is @Column(insertable = false)",
                "MQ3304: TicketRow.assigneeId: TicketEntity.assignee is @JoinColumn(insertable = false)");
    }

    @Test
    void ac_proc_15_an_insert_model_writes_an_updatable_false_column_and_a_primitive_to_one_id() {
        Compilation compilation = compile(InsertModelSources.ADDRESS, InsertModelSources.CUSTOMER_ENTITY,
                InsertModelSources.ORDER_ENTITY, source("ins.TicketEntity", TICKET_ENTITY),
                source("ins.TicketRow", """
                        package ins;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.InsertModel;
                        import com.rey.modelquery.annotations.PrimaryKey;

                        @InsertModel(root = TicketEntity.class)
                        public record TicketRow(
                                @PrimaryKey long id,
                                String createdBy,
                                @Column(attribute = "customer") long customerId) {}
                        """));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(compilation.diagnostics()).noneMatch(d -> d.getKind() != Diagnostic.Kind.NOTE);
    }

    @Test
    void ac_proc_15_insert_columns_is_a_reserved_constant() {
        Compilation compilation = compile(InsertModelSources.ORDER_ARCHIVE_ENTITY, source("ins.ArchiveRow", """
                package ins;

                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.InsertModel;
                import com.rey.modelquery.annotations.PrimaryKey;

                @InsertModel(root = OrderArchiveEntity.class)
                public record ArchiveRow(
                        @PrimaryKey Long orderId, @Column(attribute = "status") String insertColumns) {}
                """));

        assertThat(errors(compilation)).containsExactly("MQ3015: ArchiveRow.insertColumns: constant INSERT_COLUMNS "
                + "is reserved by the generated class; rename the field");
    }

    @Test
    void ac_proc_15_an_embedded_id_is_named_whole_or_by_its_components() {
        String entity = """
                package ins;

                import jakarta.persistence.EmbeddedId;
                import jakarta.persistence.Entity;

                @Entity
                public class LineEntity {
                    @EmbeddedId
                    LineId id;
                    Integer quantity;
                }
                """;
        String lineId = """
                package ins;

                import jakarta.persistence.Embeddable;
                import java.io.Serializable;

                @Embeddable
                public class LineId implements Serializable {
                    private static final long serialVersionUID = 1L;
                    Long orderId;
                    Integer lineNo;
                }
                """;
        Compilation named = compile(source("ins.LineEntity", entity), source("ins.LineId", lineId),
                source("ins.LineRow", """
                        package ins;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.InsertModel;
                        import com.rey.modelquery.annotations.PrimaryKey;

                        @InsertModel(root = LineEntity.class)
                        public record LineRow(
                                @PrimaryKey @Column(attribute = "id.orderId") Long orderId,
                                @PrimaryKey @Column(attribute = "id.lineNo") Integer lineNo,
                                Integer quantity) {}
                        """),
                source("ins.WholeLineRow", """
                        package ins;

                        import com.rey.modelquery.annotations.InsertModel;
                        import com.rey.modelquery.annotations.PrimaryKey;

                        @InsertModel(root = LineEntity.class)
                        public record WholeLineRow(@PrimaryKey LineId id, Integer quantity) {}
                        """));
        Compilation half = compile(source("ins.LineEntity", entity), source("ins.LineId", lineId),
                source("ins.HalfLineRow", """
                        package ins;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.InsertModel;
                        import com.rey.modelquery.annotations.PrimaryKey;

                        @InsertModel(root = LineEntity.class)
                        public record HalfLineRow(
                                @PrimaryKey @Column(attribute = "id.orderId") Long orderId, Integer quantity) {}
                        """));

        assertThat(named.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(ProcessorHarness.generatedFlat(named, "ins.QLineRow"))
                .contains("public static ValuesInsert.Rows<LineEntity, LineId, LineRow> insert(");
        assertThat(ProcessorHarness.generatedFlat(named, "ins.QWholeLineRow"))
                .contains(".addKey(ID, WholeLineRow::id)")
                .contains("public static ValuesInsert.Rows<LineEntity, LineId, WholeLineRow> insert(");
        assertThat(errors(half)).containsExactly(
                "MQ3501: HalfLineRow: LineEntity's id 'id' has no @GeneratedValue; name it with @PrimaryKey");
    }

    @Test
    void ac_proc_16_two_model_annotations_report_once_on_the_type_and_generate_nothing() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(InsertModelSources.ORDER_ARCHIVE_ENTITY,
                source("ins.ArchiveRow", """
                        package ins;

                        import com.rey.modelquery.annotations.InsertModel;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.QueryModel;
                        import com.rey.modelquery.annotations.UpdateModel;

                        @QueryModel(root = OrderArchiveEntity.class)
                        @UpdateModel(root = OrderArchiveEntity.class)
                        @InsertModel(root = OrderArchiveEntity.class)
                        public record ArchiveRow(@PrimaryKey Long orderId, String status) {}
                        """));

        assertThat(processor.messages())
                .extracting(
                        ProcessorHarness.Recording.Message::kind,
                        ProcessorHarness.Recording.Message::text,
                        message -> message.element().getKind(),
                        message -> message.element().toString())
                .containsExactly(tuple(Diagnostic.Kind.ERROR, "MQ3503: ArchiveRow: @QueryModel, @UpdateModel and "
                        + "@InsertModel each generate a QModel class for it; keep one", ElementKind.RECORD,
                        "ins.ArchiveRow"));
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_diag_09_mq3504_warns_on_the_model_type_and_the_qmodel_is_generated() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(source("ins.LegacyEntity", """
                        package ins;

                        import jakarta.persistence.Entity;

                        @Entity
                        public class LegacyEntity {
                            Long id;
                        }
                        """),
                source("ins.LegacyRow", """
                        package ins;

                        import com.rey.modelquery.annotations.InsertModel;

                        @InsertModel(root = LegacyEntity.class)
                        public record LegacyRow(Long id) {}
                        """));

        assertThat(processor.messages())
                .extracting(
                        ProcessorHarness.Recording.Message::kind,
                        message -> message.element().getKind(),
                        message -> message.element().toString())
                .containsExactly(tuple(Diagnostic.Kind.WARNING, ElementKind.RECORD, "ins.LegacyRow"));
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(processor.originating()).containsOnlyKeys("ins.QLegacyRow");
    }
}
