package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

/** AC-INS-23: a caller inside com.rey.modelquery.* is its own call site, never a JUnit or engine frame. */
class AcInsCallSiteTest extends AuditTestBase {

    @Test
    void ac_ins_23_caller_in_the_library_package_is_the_call_site_not_a_junit_frame() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();
            int line = new Throwable().getStackTrace()[0].getLineNumber() + 1;
            AuditOrderView order = executor.one(query).orElseThrow();
            assertThat(AuditObserver.tagOf(order).callSite()).isEqualTo("AcInsCallSiteTest."
                    + "ac_ins_23_caller_in_the_library_package_is_the_call_site_not_a_junit_frame:" + line);
        }
    }
}
