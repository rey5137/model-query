package org.example.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.audit.AuditProbe;
import com.rey.modelquery.audit.AuditTestBase;
import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-INS-22: the report names the real test methods and counts them, with no fake capture, so the JUnit session
 * listener must be feeding the call-site capture on this runner.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
public class AcIns22TestIdentityTest extends AuditTestBase {

    @BeforeAll
    static void clearReport() {
        AuditProbe.clear();
    }

    @Test
    void a_first_test_reads_only_the_id() {
        callAndReadId();
    }

    @Test
    void b_second_test_reads_only_the_id() {
        callAndReadId();
    }

    @Test
    void c_report_names_both_tests_and_counts_two(@TempDir Path dir) throws Exception {
        assertThat(AuditProbe.currentTest()).isEqualTo(getClass().getName() + "#c_report_names_both_tests_and_counts_two");
        assertThat(AuditProbe.tests("AcIns22TestIdentityTest.")).containsExactly(
                getClass().getName() + "#a_first_test_reads_only_the_id",
                getClass().getName() + "#b_second_test_reads_only_the_id");
        String merged = AuditProbe.mergedReport(dir);
        assertThat(merged).contains("never read in 2 calls across 2 tests");
    }

    private void callAndReadId() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();
            AuditOrderView order = AuditCaller.executorCall(() -> executor.one(query)).orElseThrow();
            assertThat(order.id()).isEqualTo(1L);
        }
    }
}
