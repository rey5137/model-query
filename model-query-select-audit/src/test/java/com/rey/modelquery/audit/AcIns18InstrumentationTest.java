package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.rey.modelquery.audit.test.AuditOrderBean;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.QAuditOrderBean;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.SelectSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

/** AC-INS-18: later Mockito instrumentation preserves the read hooks. */
class AcIns18InstrumentationTest extends AuditTestBase {
    @Test
    void ac_ins_18_record_hook_survives_inline_mock_and_spy() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.ID))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            assertThat(ReadTransformer.hooked(model.getClass())).isTrue();

            var mock = mock(AuditOrderView.class);
            when(mock.notes()).thenReturn("mocked");
            assertThat(mock.notes()).isEqualTo("mocked");
            assertThat(mockingDetails(mock).isMock()).isTrue();
            assertThatThrownBy(model::notes).isInstanceOf(UnselectedColumnRead.class);

            var copy = spy(model);
            assertThat(mockingDetails(copy).isSpy()).isTrue();
            assertThatThrownBy(model::notes).isInstanceOf(UnselectedColumnRead.class);
        }
    }

    @Test
    void ac_ins_18_class_hook_survives_inline_mock_and_spy() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderBean.query().select(SelectSet.of(QAuditOrderBean.ID))
                    .where(f -> f.eq(QAuditOrderBean.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            assertThat(ReadTransformer.hooked(model.getClass())).isTrue();

            var mock = mock(AuditOrderBean.class);
            when(mock.getNotes()).thenReturn("mocked");
            assertThat(mock.getNotes()).isEqualTo("mocked");
            assertThat(mockingDetails(mock).isMock()).isTrue();
            assertThatThrownBy(model::getNotes).isInstanceOf(UnselectedColumnRead.class);

            var copy = spy(model);
            assertThat(mockingDetails(copy).isSpy()).isTrue();
            assertThatThrownBy(model::getNotes).isInstanceOf(UnselectedColumnRead.class);
        }
    }
}
