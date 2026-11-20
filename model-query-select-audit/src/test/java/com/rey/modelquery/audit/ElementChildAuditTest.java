package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.audit.test.QAuditOrderSpots;
import com.rey.modelquery.audit.test.QAuditSpotRow;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

class ElementChildAuditTest extends AuditTestBase {
    @Test
    void ac_fch_38_element_child_handoff_counts_getter_reads() {
        try (EntityManager em = sessions.createEntityManager()) {
            var plan = FetchPlan.of(QAuditOrderSpots.ALL)
                    .child(QAuditOrderSpots.SPOTS, FetchPlan.of(QAuditSpotRow.ALL));
            var query = QAuditOrderSpots.query().where(f -> f.eq(QAuditOrderSpots.ID, 1L)).fetch(plan).build();
            var spot = orderExecutor(em).list(query, Limit.unlimited()).get(0).spots().get(0);
            assertThat(AuditObserver.tagOf(spot)).isNotNull();
            assertThat(AuditObserver.tagOf(spot).wasRead("city")).isFalse();
            assertThat(spot.city()).isEqualTo("Rome");
            assertThat(AuditObserver.tagOf(spot).wasRead("city")).isTrue();
        }
    }
}
