package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.FlatPathHooks;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** AC-COL-53: flat-row mapping for eligible reads (P-B.2, R-COL-11, D-184). */
class FlatRowMappingTest {

    record OrderView(String ref, OrderStatus status, Object rawRef, Object rawStatus) {

        long id() {
            return (Long) rawRef;
        }
    }

    static final class OrderClassView {
        private final String ref;
        private final OrderStatus status;
        private final Object rawRef;
        private final Object rawStatus;

        OrderClassView(String ref, OrderStatus status, Object rawRef, Object rawStatus) {
            this.ref = ref;
            this.status = status;
            this.rawRef = rawRef;
            this.rawStatus = rawStatus;
        }

        public String ref() {
            return ref;
        }

        public OrderStatus status() {
            return status;
        }

        public Object rawRef() {
            return rawRef;
        }

        public Object rawStatus() {
            return rawStatus;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OrderClassView that)) return false;
            return Objects.equals(ref, that.ref) && status == that.status
                    && Objects.equals(rawRef, that.rawRef) && Objects.equals(rawStatus, that.rawStatus);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ref, status, rawRef, rawStatus);
        }

        @Override
        public String toString() {
            return "OrderClassView[" + ref + ", " + status + ", " + rawRef + ", " + rawStatus + "]";
        }
    }

    static final class RefConverter implements ColumnConverter<String, Long> {
        static final RefConverter INSTANCE = new RefConverter();

        @Override
        public String toModel(Long attribute) {
            return "#" + attribute;
        }

        @Override
        public Long toAttribute(String model) {
            return Long.valueOf(model.substring(1));
        }
    }

    static final class StatusConverter implements ColumnConverter<OrderStatus, String> {
        static final StatusConverter INSTANCE = new StatusConverter();

        @Override
        public OrderStatus toModel(String attribute) {
            return OrderStatus.valueOf(attribute);
        }

        @Override
        public String toAttribute(OrderStatus model) {
            return model.name();
        }
    }

    private static final QueryCustomizer NO_OP = (spec, joins, query, cb, phase) -> {};

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    private static final ColumnField<OrderView, OrderEntity, String> REF =
            ColumnField.of(OrderView.class, ROOT, "id", String.class, Long.class, RefConverter.INSTANCE);
    private static final ColumnField<OrderView, OrderEntity, OrderStatus> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", OrderStatus.class, String.class, StatusConverter.INSTANCE);

    private static final ColumnField<OrderClassView, OrderEntity, String> CLASS_REF =
            ColumnField.of(OrderClassView.class, ROOT, "id", String.class, Long.class, RefConverter.INSTANCE);
    private static final ColumnField<OrderClassView, OrderEntity, OrderStatus> CLASS_STATUS =
            ColumnField.of(OrderClassView.class, ROOT, "status", OrderStatus.class, String.class,
                    StatusConverter.INSTANCE);

    private static final TableField<OrderEntity, OrderItemEntity> ITEMS =
            TableField.join(ROOT, "items", JoinType.INNER);
    private static final ColumnField<OrderView, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(OrderView.class, ITEMS, "productCode", String.class);

    private static final ModelQuery.Builder<OrderEntity, String, OrderView> ORDERS = ModelQuery
            .builder(ROOT, row -> new OrderView(row.get(REF), row.get(STATUS), row.raw(REF), row.raw(STATUS)))
            .select(SelectSet.of(REF, STATUS))
            .primaryKey(PrimaryKey.of(REF));

    private static final ModelQuery.Builder<OrderEntity, String, OrderClassView> CLASS_ORDERS = ModelQuery
            .builder(ROOT, row -> new OrderClassView(
                    row.get(CLASS_REF), row.get(CLASS_STATUS), row.raw(CLASS_REF), row.raw(CLASS_STATUS)))
            .select(SelectSet.of(CLASS_REF, CLASS_STATUS))
            .primaryKey(PrimaryKey.of(CLASS_REF));

    @TckTest
    void ac_col_53_eligible_flat_read_matches_general_path_for_record_and_class_models(TckDatabase db) {
        withSession(JoinTestSupport.dataSource(db), (executor, em) -> {
            var qRecordFlat = ORDERS.orderBy(STATUS.asc(), REF.asc()).build();
            var qRecordGeneral = ORDERS.orderBy(STATUS.asc(), REF.asc()).customize(NO_OP).build();

            // Eligibility check confirms paths
            assertThat(FlatPathHooks.isFlatEligible(qRecordFlat, em.getCriteriaBuilder())).isTrue();
            assertThat(FlatPathHooks.isFlatEligible(qRecordGeneral, em.getCriteriaBuilder())).isFalse();

            // 1. Record model: flat path returns same models as general path (which uses NO_OP customizer)
            List<OrderView> flatRecord = executor.list(qRecordFlat, Limit.of(25));
            List<OrderView> generalRecord = executor.list(qRecordGeneral, Limit.of(25));
            assertThat(flatRecord).hasSize(25).isEqualTo(generalRecord);

            // Raw key and cursor read unconverted
            assertThat(flatRecord.get(0).ref()).startsWith("#");
            assertThat(flatRecord.get(0).rawRef()).isInstanceOf(Long.class);
            assertThat(flatRecord.get(0).status()).isInstanceOf(OrderStatus.class);
            assertThat(flatRecord.get(0).rawStatus()).isInstanceOf(String.class);

            // 2. Class model: flat path matches general path
            var qClassFlat = CLASS_ORDERS.orderBy(CLASS_STATUS.asc(), CLASS_REF.asc()).build();
            var qClassGeneral = CLASS_ORDERS.orderBy(CLASS_STATUS.asc(), CLASS_REF.asc()).customize(NO_OP).build();

            assertThat(FlatPathHooks.isFlatEligible(qClassFlat, em.getCriteriaBuilder())).isTrue();
            assertThat(FlatPathHooks.isFlatEligible(qClassGeneral, em.getCriteriaBuilder())).isFalse();

            List<OrderClassView> flatClass = executor.list(qClassFlat, Limit.of(25));
            List<OrderClassView> generalClass = executor.list(qClassGeneral, Limit.of(25));
            assertThat(flatClass).hasSize(25).isEqualTo(generalClass);

            // 3. Keyset paging: cursor continuity across pages
            var keysetQ = ORDERS.orderBy(STATUS.asc(), REF.asc()).keyset().build();
            var firstPage = executor.page(keysetQ, KeysetSpec.first(10));
            assertThat(firstPage.content()).hasSize(10);
            assertThat(firstPage.nextCursor()).isPresent();
            String nextCursor = firstPage.nextCursor().orElseThrow();

            var secondPage = executor.page(keysetQ, KeysetSpec.after(nextCursor, 10));
            assertThat(secondPage.content()).hasSize(10);
            assertThat(secondPage.content().get(0).id()).isNotEqualTo(firstPage.content().get(9).id());
        });
    }

    @TckTest
    void ac_col_53_ineligible_reads_take_general_path(TckDatabase db) {
        withSession(JoinTestSupport.dataSource(db), (executor, em) -> {
            var cb = em.getCriteriaBuilder();

            // A. Plain query is eligible
            var qPlain = ORDERS.orderBy(REF.asc()).build();
            assertThat(FlatPathHooks.isFlatEligible(qPlain, cb)).isTrue();

            // B. Query with fetch plan is ineligible
            var qFetch = ORDERS.orderBy(REF.asc())
                    .fetch(FetchPlan.of(SelectSet.of(REF)))
                    .build();
            assertThat(FlatPathHooks.isFlatEligible(qFetch, cb)).isFalse();

            // C. Kept to-many join: order term through to-many join kept by R-EXE-16
            var qKeptOrder = ORDERS.orderBy(ITEM_PRODUCT.asc(), REF.asc()).build();
            assertThat(FlatPathHooks.isFlatEligible(qKeptOrder, cb)).isFalse();

            // D. Kept to-many join: customizer to-many join
            var qCustomJoin = ORDERS.customize(JoinTestSupport.itemJoin("PROD-1")).build();
            assertThat(FlatPathHooks.isFlatEligible(qCustomJoin, cb)).isFalse();

            // E. Customizer attached to the query is ineligible
            var qCustom = ORDERS.orderBy(REF.asc()).customize(NO_OP).build();
            assertThat(FlatPathHooks.isFlatEligible(qCustom, cb)).isFalse();
        });
    }

    @TckTest
    void ac_col_53_limit_integer_max_value_does_not_presize(TckDatabase db) {
        withSession(JoinTestSupport.dataSource(db), (executor, em) -> {
            var q = ORDERS.orderBy(REF.asc()).build();
            assertThat(FlatPathHooks.isFlatEligible(q, em.getCriteriaBuilder())).isTrue();
            List<OrderView> rows = executor.list(q, Limit.of(Integer.MAX_VALUE));
            assertThat(rows).hasSize(TckFixture.ORDERS);
        });
    }

    @FunctionalInterface
    interface SessionWork {
        void run(ModelQueryExecutor<OrderEntity> executor, EntityManager em);
    }

    private static void withSession(DataSource ds, SessionWork work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.run(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()), em));
        }
    }
}
