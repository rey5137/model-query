package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

/**
 * AC-INS-27: with {@code attach=startup}, no {@code -javaagent} and {@code missingAgent=off}, the session opens and
 * runs unaudited instead of failing (R-INS-22, D-265). Runs in its own Failsafe execution without the agent.
 */
class MissingAgentOffRun extends AuditTestBase {

    @Test
    void ac_ins_27_missing_agent_off_runs_unaudited() {
        assertThat(AuditAgent.isInstalled()).isFalse();
        assertThat(AuditAgent.disabled()).isTrue();
        ReadEvents.clear();
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();
            assertThat(executor.one(query)).isPresent();
            assertThat(SelectAuditReport.render()).doesNotContain("UNREAD COLUMNS");
        }
    }
}
