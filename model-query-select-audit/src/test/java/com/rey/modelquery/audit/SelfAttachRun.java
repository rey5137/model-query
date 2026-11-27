package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import org.example.audit.AuditCaller;
import org.junit.jupiter.api.Test;

/**
 * AC-INS-26: with no {@code -javaagent} and the default {@code attach=auto}, the JUnit session attaches the agent
 * itself when it opens and the audit still reports an unread column (R-INS-22, D-265). Runs in its own Failsafe
 * execution without the agent.
 */
class SelfAttachRun extends AuditTestBase {

    @Test
    void ac_ins_26_session_attaches_the_agent_and_audits() {
        assertThat(AuditAgent.isInstalled()).isTrue();
        assertThat(AuditAgent.attachedAtRuntime()).isTrue();
        ReadEvents.clear();
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();
            AuditOrderView order = AuditCaller.executorCall(() -> executor.one(query)).orElseThrow();
            assertThat(order.id()).isEqualTo(1L);
            assertThat(SelectAuditReport.render()).contains("UNREAD COLUMNS (warning)")
                    .contains("AuditOrderView.total, AuditOrderView.notes");
        }
    }
}
