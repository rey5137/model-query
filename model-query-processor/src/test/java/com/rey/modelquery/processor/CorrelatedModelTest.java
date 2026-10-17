package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import org.junit.jupiter.api.Test;

/** Generated correlated expressions use the core definition checks (R-PROC-21, R-PROC-22, R-COL-33). */
class CorrelatedModelTest {

    @Test
    void ac_col_41_a_computed_correlated_value_initializes_as_an_expression() throws Exception {
        ClassLoader loader = model("", "@Computed(CountRows.class)");
        Object field = loader.loadClass("shop.QCorrelatedView").getField("VALUE").get(null);
        assertThat(field).isInstanceOf(ExpressionField.class);
    }

    @Test
    void ac_col_41_an_aggregate_expression_that_is_correlated_is_mq1510() {
        ClassLoader loader = model("@GroupBy", "@Aggregate(fn = AggregateFunction.SUM, expression = CountRows.class)");
        assertThatThrownBy(() -> Class.forName("shop.QCorrelatedView", true, loader))
                .isInstanceOf(ExceptionInInitializerError.class)
                .cause().isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        error -> assertThat(error.code()).isEqualTo(MqCode.MQ1510));
    }

    @Test
    void ac_col_41_a_computed_correlated_group_key_is_mq1314() throws Exception {
        Class<?> queryModel = model("@GroupBy", "@GroupBy @Computed(CountRows.class)")
                .loadClass("shop.QCorrelatedView");
        assertThatThrownBy(() -> queryModel.getMethod("query").invoke(null))
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .cause().isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        error -> assertThat(error.code()).isEqualTo(MqCode.MQ1314));
    }

    private static ClassLoader model(String idAnnotation, String valueAnnotation) {
        Compilation compilation = compile(source("shop.OrderEntity", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class OrderEntity { @Id Long id; }
                """), source("shop.CorrelatedView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = OrderEntity.class)
                public record CorrelatedView(%s Long id, %s Long value) {}
                """.formatted("@PrimaryKey " + idAnnotation, valueAnnotation)), source("shop.CountRows", """
                package shop;
                import com.rey.modelquery.core.*;
                public class CountRows implements ExpressionDefinition<CorrelatedView, Long> {
                    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
                    private static final ColumnField<CorrelatedView, OrderEntity, Long> ID =
                            ColumnField.of(CorrelatedView.class, ROOT, "id", Long.class);
                    public ExpressionField<CorrelatedView, Long> expression() {
                        return Correlated.count(CorrelatedView.class, SubSelect.of(ID),
                                (f, outer) -> f.compare(ID, Op.EQ, outer.column(ID)));
                    }
                }
                """));
        assertThat(compilation).succeededWithoutWarnings();
        return classes(compilation);
    }
}
