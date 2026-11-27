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
 * AC-INS-27: with no agent, {@code missingAgent=warn} opens the session and runs it unaudited instead of failing,
 * both under {@code attach=startup} and after an {@code attach=auto} that could not attach (R-INS-22, D-265). Runs in
 * its own Failsafe executions without the agent.
 */
class MissingAgentWarnRun extends AuditTestBase {

    @Test
    void ac_ins_27_missing_agent_warn_runs_unaudited() {
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
