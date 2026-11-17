package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.audit.test.AuditCustomerView;
import com.rey.modelquery.audit.test.AuditLineView;
import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.ProjectionAuditCustomerView;
import com.rey.modelquery.audit.test.QAuditLineView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC-INS-20: A {@code @Join} is selected by its presence key, an empty {@code Optional} tags nothing;
 * a {@code @Child} loaded by the plan is tagged from the child query's rows and reported as {@code child}.
 */
class AcIns20Test extends AuditTestBase {

    @Test
    void ac_ins_20_deep_join_uses_parent_paths_for_a_cross_package_bound_q() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL.with(QAuditOrderView.CUSTOMER)
                    .with(QAuditOrderView.fields().set("customer.colleague").orElseThrow()))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var order = orderExecutor(em).one(query).orElseThrow();
            var customer = order.customer().orElseThrow();
            var colleague = customer.colleague().orElseThrow();
            assertThat(AuditObserver.tagOf(customer).isSelected("colleague")).isTrue();
            assertThat(AuditObserver.tagOf(colleague).isSelected("id")).isTrue();
            assertThat(AuditObserver.tagOf(colleague).isSelected("name")).isTrue();
        }
    }

    @Test
    void ac_ins_20_join_presence_uses_parent_paths_with_configured_q_prefix() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL.with(QAuditOrderView.CUSTOMER))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            AuditOrderView order = executor.one(query).orElseThrow();
            Tag orderTag = AuditObserver.tagOf(order);
            assertThat(orderTag).isNotNull();
            assertThat(orderTag.isSelected("customer")).isTrue();

            assertThat(order.customer()).isPresent();
            AuditCustomerView customer = order.customer().get();
            Tag customerTag = AuditObserver.tagOf(customer);
            assertThat(customerTag).isNotNull();
            assertThat(customerTag.callSite()).isEqualTo(orderTag.callSite());
            assertThat(customerTag.isSelected("id")).isTrue();
            assertThat(customerTag.isSelected("name")).isTrue();
        }
    }

    @Test
    void ac_ins_20_empty_optional_tags_nothing() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL.with(QAuditOrderView.CUSTOMER))
                    .where(f -> f.eq(QAuditOrderView.ID, 3L))
                    .build();

            AuditOrderView order = executor.one(query).orElseThrow();
            Tag orderTag = AuditObserver.tagOf(order);
            assertThat(orderTag).isNotNull();
            assertThat(order.customer()).isEmpty();
        }
    }

    @Test
    void ac_ins_20_child_loaded_by_plan_is_tagged_from_child_query_rows_and_reported_as_child() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            FetchPlan<AuditOrderView> plan = FetchPlan.of(QAuditOrderView.ALL)
                    .child(QAuditOrderView.LINES, FetchPlan.of(QAuditLineView.ALL));

            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .fetch(plan)
                    .build();

            AuditOrderView order = executor.one(query).orElseThrow();
            Tag orderTag = AuditObserver.tagOf(order);
            assertThat(orderTag).isNotNull();
            assertThat(orderTag.isSelected("lines")).isTrue();

            List<AuditLineView> lines = order.lines();
            assertThat(lines).hasSize(2);
            for (AuditLineView line : lines) {
                Tag lineTag = AuditObserver.tagOf(line);
                assertThat(lineTag).isNotNull();
                assertThat(lineTag.callSite()).isEqualTo(orderTag.callSite());
                assertThat(lineTag.method()).contains("child lines");
                assertThat(lineTag.isSelected("sku")).isTrue();
            }
        }
    }

    @Test
    void ac_ins_20_unloaded_child_is_unselected() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL.with(QAuditOrderView.CUSTOMER))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            AuditOrderView order = executor.one(query).orElseThrow();
            Tag orderTag = AuditObserver.tagOf(order);
            assertThat(orderTag).isNotNull();
            assertThat(orderTag.isSelected("lines")).isFalse();
        }
    }

    @Test
    void ac_ins_20_join_without_presence_key_is_unselected() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            // Select only id and total (leaving out customer's columns)
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(SelectSet.of(QAuditOrderView.ID, QAuditOrderView.TOTAL))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            AuditOrderView order = executor.one(query).orElseThrow();
            Tag orderTag = AuditObserver.tagOf(order);
            assertThat(orderTag).isNotNull();
            assertThat(orderTag.isSelected("customer")).isFalse();
        }
    }
}
