package com.rey.modelquery.tck.col;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;

/** Columns whose attribute is a dotted path through embedded values (spec api/10 R-COL-08, D-41). */
class EmbeddedPathTest {

    record OrderView(Long id, BigDecimal total, BigDecimal amount, LocalDateTime placedAt, LocalDateTime at) {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ROOT, "items", INNER);

    private static final ColumnField<OrderView, OrderEntity, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderView.class, ROOT, "total", BigDecimal.class);
    private static final ColumnField<OrderView, OrderEntity, LocalDateTime> PLACED_AT =
            ColumnField.of(OrderView.class, ROOT, "placedAt", LocalDateTime.class);
    private static final ColumnField<OrderView, OrderEntity, BigDecimal> AMOUNT =
            ColumnField.of(OrderView.class, ROOT, "summary.amount", BigDecimal.class);
    private static final ColumnField<OrderView, OrderEntity, LocalDateTime> AT =
            ColumnField.of(OrderView.class, ROOT, "summary.placement.at", LocalDateTime.class);

    private static final BigDecimal THRESHOLD = new BigDecimal("900.00");

    private static final ModelQuery.Builder<OrderEntity, Long, OrderView> ORDERS = ModelQuery
            .builder(ROOT, row -> new OrderView(
                    row.get(ID), row.get(TOTAL), row.get(AMOUNT), row.get(PLACED_AT), row.get(AT)))
            .select(SelectSet.of(ID, TOTAL, AMOUNT, PLACED_AT, AT))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());

    @TckTest
    void ac_col_10_a_dotted_path_through_embedded_values_selects_and_filters_the_value(TckDatabase db) {
        List<OrderView> byEmbedded = new ArrayList<>();
        List<OrderView> byPlain = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "col-10-embedded-path", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> byEmbedded.addAll(
                        ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())
                                .list(ORDERS.where(f -> f.gt(AMOUNT, THRESHOLD)).build(), Limit.unlimited())));
            }
        });
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> byPlain.addAll(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())
                            .list(ORDERS.where(f -> f.gt(TOTAL, THRESHOLD)).build(), Limit.unlimited())));
        }

        assertThat(byEmbedded).isNotEmpty().hasSizeLessThan(TckFixture.ORDERS).isEqualTo(byPlain);
        assertThat(byEmbedded).allSatisfy(order -> {
            assertThat(order.amount()).isEqualTo(order.total()).isGreaterThan(THRESHOLD);
            assertThat(order.at()).isEqualTo(order.placedAt()).isNotNull();
        });
    }

    @TckTest
    void ac_col_10_a_dotted_path_is_type_checked_at_its_last_segment(TckDatabase db) {
        var amountAsLong = ColumnField.of(OrderView.class, ROOT, "summary.amount", Long.class);
        inContext(db, (root, ctx) -> assertThatThrownBy(() -> amountAsLong.path(ctx))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1001))
                .hasMessage("MQ1001: OrderView.summary.amount: declared Long, entity attribute "
                        + "OrderEntity.summary.amount is BigDecimal"));
    }

    @TckTest
    void ac_col_10_an_unknown_segment_throws_mq1002_naming_the_segment(TckDatabase db) {
        var unknownFirst = ColumnField.of(OrderView.class, ROOT, "nope.amount", BigDecimal.class);
        var unknownLast = ColumnField.of(OrderView.class, ROOT, "summary.nope", BigDecimal.class);
        var unknownMiddle = ColumnField.of(OrderView.class, ROOT, "summary.nope.at", LocalDateTime.class);
        var throughBasic = ColumnField.of(OrderView.class, ROOT, "status.length", Integer.class);
        inContext(db, (root, ctx) -> {
            assertMq1002(() -> unknownFirst.path(ctx),
                    "MQ1002: OrderView.nope.amount: OrderEntity has no attribute 'nope'")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertMq1002(() -> unknownLast.path(ctx),
                    "MQ1002: OrderView.summary.nope: OrderSummary has no attribute 'nope'")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertMq1002(() -> unknownMiddle.path(ctx),
                    "MQ1002: OrderView.summary.nope.at: OrderSummary has no attribute 'nope'");
            assertMq1002(() -> throughBasic.path(ctx),
                    "MQ1002: OrderView.status.length: segment 'status' is not an embedded value, so "
                            + "OrderEntity.status has no attribute 'length'");
        });
    }

    @TckTest
    void ac_col_10_a_segment_crossing_an_association_throws_mq1002_naming_the_segment(TckDatabase db) {
        var toOne = ColumnField.of(OrderView.class, ROOT, "customer.country", String.class);
        var toMany = ColumnField.of(OrderView.class, ROOT, "items.quantity", Integer.class);
        var insideEmbedded = ColumnField.of(OrderView.class, ROOT, "summary.referredBy.country", String.class);
        var onJoin = ColumnField.of(OrderView.class, ITEMS, "order.id", Long.class);
        inContext(db, (root, ctx) -> {
            assertMq1002(() -> toOne.path(ctx),
                    "MQ1002: OrderView.customer.country: segment 'customer' crosses the association "
                            + "OrderEntity.customer; a column's path may only go through embedded values, so join "
                            + "the association with a TableField");
            assertMq1002(() -> toMany.path(ctx),
                    "MQ1002: OrderView.items.quantity: segment 'items' crosses the association OrderEntity.items; "
                            + "a column's path may only go through embedded values, so join the association with a "
                            + "TableField");
            assertMq1002(() -> insideEmbedded.path(ctx),
                    "MQ1002: OrderView.summary.referredBy.country: segment 'referredBy' crosses the association "
                            + "OrderSummary.referredBy; a column's path may only go through embedded values, so join "
                            + "the association with a TableField");
            // Nothing was joined on the way to the refusal.
            assertThat(root.getJoins()).isEmpty();
            // On a joined table the segments are looked up on the joined entity, not on the query root.
            assertMq1002(() -> onJoin.path(ctx),
                    "MQ1002: OrderView.order.id: segment 'order' crosses the association OrderItemEntity.order; "
                            + "a column's path may only go through embedded values, so join the association with a "
                            + "TableField");
        });
    }

    @TckTest
    void ac_col_10_a_dotted_path_on_a_join_over_basic_values_throws_mq1002(TckDatabase db) {
        TableField<OrderEntity, String> tags = TableField.join(ROOT, "tags", INNER);
        var intoBasic = ColumnField.of(OrderView.class, tags, "value.length", Integer.class);
        inContext(db, (root, ctx) -> assertMq1002(() -> intoBasic.path(ctx),
                "MQ1002: OrderView.value.length: String is a basic value, so it has no attribute 'value'"));
    }

    private static void inContext(TckDatabase db, BiConsumer<Root<OrderEntity>, JoinContext> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                Root<OrderEntity> root = cb.createTupleQuery().from(OrderEntity.class);
                work.accept(root, JoinContext.of(root, cb));
            });
        }
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertMq1002(
            ThrowingCallable resolution, String message) {
        return assertThatThrownBy(resolution)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1002))
                .hasMessage(message);
    }
}
