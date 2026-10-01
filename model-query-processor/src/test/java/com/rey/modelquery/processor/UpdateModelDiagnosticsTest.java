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

/** The update-model diagnostics beyond the matrix's one case per code (spec processor/32 §1, D-70). */
class UpdateModelDiagnosticsTest {

    @Test
    void ac_diag_01_mq3306_a_composite_key_missing_a_part_is_reported_on_the_model() {
        Compilation compilation = compile(UpdateModelSources.STOCK_ENTITY, UpdateModelSources.STOCK_ID,
                source("patch.StockPatch", """
                        package patch;

                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.UpdateModel;

                        @UpdateModel(root = StockEntity.class)
                        public record StockPatch(@PrimaryKey Long warehouseId, Integer quantity) {}
                        """));

        assertThat(errors(compilation)).containsExactly("MQ3306: StockPatch: @PrimaryKey must be StockEntity's id "
                + "'productId', 'warehouseId'; bulk writes key on the entity id");
    }

    @Test
    void ac_diag_01_mq3306_a_keyless_generate_changes_summary_model_is_reported() {
        Compilation compilation = compile(UpdateModelSources.ADDRESS, UpdateModelSources.CUSTOMER_ENTITY,
                UpdateModelSources.ORDER_ENTITY, source("patch.StatusCount", """
                        package patch;

                        import com.rey.modelquery.annotations.Aggregate;
                        import com.rey.modelquery.annotations.AggregateFunction;
                        import com.rey.modelquery.annotations.GroupBy;
                        import com.rey.modelquery.annotations.QueryModel;

                        @QueryModel(root = OrderEntity.class, generateChanges = true)
                        public record StatusCount(
                                @GroupBy String status, @Aggregate(fn = AggregateFunction.COUNT) Long orders) {}
                        """));

        assertThat(errors(compilation)).containsExactly("MQ3306: StatusCount: @PrimaryKey must be OrderEntity's id "
                + "'id'; bulk writes key on the entity id");
    }

    @Test
    void ac_diag_01_mq3305_a_primitive_field_writes_a_to_one_whose_id_is_its_wrapper() {
        Compilation compilation = compile(UpdateModelSources.ADDRESS, UpdateModelSources.CUSTOMER_ENTITY,
                UpdateModelSources.ORDER_ENTITY, source("patch.CustomerPatch", """
                        package patch;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.UpdateModel;

                        @UpdateModel(root = OrderEntity.class)
                        public record CustomerPatch(
                                @PrimaryKey long id, @Column(attribute = "customer") long customerId) {}
                        """));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @Test
    void ac_diag_03_an_update_model_diagnostic_is_attached_to_its_field() {
        var processor = new ProcessorHarness.Recording();

        processor.compile(UpdateModelSources.ADDRESS, UpdateModelSources.CUSTOMER_ENTITY,
                UpdateModelSources.ORDER_ENTITY, source("patch.OrderPatch", """
                        package patch;

                        import com.rey.modelquery.annotations.Column;
                        import com.rey.modelquery.annotations.PrimaryKey;
                        import com.rey.modelquery.annotations.UpdateModel;

                        @UpdateModel(root = OrderEntity.class)
                        public class OrderPatch {
                            @PrimaryKey
                            private Long id;
                            @Column(attribute = "customer")
                            private Integer customerId;
                        }
                        """));

        assertThat(processor.messages())
                .extracting(
                        ProcessorHarness.Recording.Message::kind,
                        ProcessorHarness.Recording.Message::text,
                        message -> message.element().getKind(),
                        message -> message.element().toString())
                .containsExactly(tuple(Diagnostic.Kind.ERROR,
                        "MQ3305: OrderPatch.customerId: CustomerEntity's id is Long, found Integer",
                        ElementKind.FIELD, "customerId"));
    }
}
