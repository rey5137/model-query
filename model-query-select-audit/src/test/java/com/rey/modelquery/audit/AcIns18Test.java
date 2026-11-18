package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderBean;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.QAuditOrderBean;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.SelectSet;
import jakarta.persistence.EntityManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** AC-INS-18: unselected reads fail at their application reader. */
class AcIns18Test extends AuditTestBase {
    private static int readerLine;

    private static String helperOne(AuditOrderView model) {
        return helperTwo(model);
    }

    private static String helperTwo(AuditOrderView model) {
        readerLine = new Throwable().getStackTrace()[0].getLineNumber() + 1;
        return model.notes();
    }

    @Test
    void ac_ins_18_reader_and_call_site_survive_two_helper_methods() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var allModel = orderExecutor(em).one(query).orElseThrow();
            assertThat(AuditObserver.tagOf(allModel).selectLabel()).isEqualTo("ALL");

            var partial = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.ID))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(partial).orElseThrow();
            String callSite = AuditObserver.tagOf(model).callSite();
            assertThatThrownBy(() -> helperOne(model)).isInstanceOf(UnselectedColumnRead.class)
                    .satisfies(error -> assertThat(error.getMessage())
                            .contains("read at   " + getClass().getName() + ".helperTwo(AcIns18Test.java:"
                                    + readerLine + ")")
                            .contains("model of  " + callSite + " one, select [AuditOrderView.id]")
                            .contains("add QAuditOrderView.NOTES to the select set"));
        }
    }
    @Test
    void ac_ins_18_one_and_list_fail_at_the_reader_and_record_the_read() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.ID))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var executor = orderExecutor(em);
            var one = executor.one(query).orElseThrow();
            var list = executor.list(query, Limit.of(1)).get(0);
            for (var model : java.util.List.of(one, list)) {
                assertThatThrownBy(() -> model.notes()).isInstanceOf(UnselectedColumnRead.class)
                        .hasMessageContaining("AuditOrderView.notes was not selected")
                        .hasMessageContaining("AcIns18Test.java:")
                        .hasMessageContaining("model of")
                        .hasMessageContaining("add QAuditOrderView.NOTES to the select set");
                assertThat(AuditObserver.tagOf(model).unselectedReads()).hasSize(1);
            }
        }
    }

    @Test
    void ac_ins_18_selected_null_is_a_read_and_does_not_fail() {
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new AuditOrderEntity(91L, 9L, null, null));
            em.getTransaction().commit();
            var query = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.ID, QAuditOrderView.NOTES))
                    .where(f -> f.eq(QAuditOrderView.ID, 91L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            assertThat(model.notes()).isNull();
            assertThat(AuditObserver.tagOf(model).wasRead("notes")).isTrue();
            assertThat(AuditObserver.tagOf(model).unselectedReads()).isEmpty();
        }
    }

    @Test
    void ac_ins_18_fail_on_none_records_without_throwing() {
        System.setProperty("modelquery.selectAudit.failOn", "none");
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.ID))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            assertThat(model.notes()).isNull();
            assertThat(AuditObserver.tagOf(model).unselectedReads()).hasSize(1);
            assertThat(AuditObserver.tagOf(model).wasRead("notes")).isFalse();
        } finally {
            System.clearProperty("modelquery.selectAudit.failOn");
        }
    }

    @Test
    void ac_ins_18_unselected_join_and_child_use_property_names_in_remedy() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.ID))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            assertThatThrownBy(model::customer).isInstanceOf(UnselectedColumnRead.class)
                    .hasMessageContaining("add customer to the select set");
            assertThatThrownBy(model::lines).isInstanceOf(UnselectedColumnRead.class)
                    .hasMessageContaining("add lines to the select set");
        }
    }

    @Test
    void ac_ins_18_class_models_and_lombok_equals_through_one_and_list() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderBean.query().select(SelectSet.of(QAuditOrderBean.ID))
                    .where(f -> f.eq(QAuditOrderBean.ID, 1L)).build();
            var executor = orderExecutor(em);
            var one = executor.one(query).orElseThrow();
            var list = executor.list(query, Limit.of(1));
            AuditOrderBean expected = new AuditOrderBean();
            expected.setId(1L);
            assertThat(list).containsExactly(expected);
            assertThat(AuditObserver.tagOf(list.get(0)).wasRead("id")).isFalse();
            assertThat(AuditObserver.tagOf(list.get(0)).unselectedReads()).isEmpty();
            assertThatThrownBy(() -> one.getNotes()).isInstanceOf(UnselectedColumnRead.class);
            assertThatThrownBy(() -> list.get(0).getNotes()).isInstanceOf(UnselectedColumnRead.class);
        }
    }

    @Test
    void ac_ins_23_unjoined_pool_read_is_labelled() throws Exception {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.ID))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            CountDownLatch finished = new CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<UnselectedColumnRead>();
            var pool = Executors.newSingleThreadExecutor();
            try {
                pool.execute(() -> {
                    try {
                        model.notes();
                    } catch (UnselectedColumnRead expected) {
                        failure.set(expected);
                    } finally {
                        finished.countDown();
                    }
                });
                assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).hasMessageContaining("may not have failed a test");
                assertThat(AuditObserver.tagOf(model).unselectedReads()).singleElement()
                        .satisfies(event -> assertThat(event.otherThread()).isTrue());
                assertThat(ReadEvents.snapshot().keySet()).anySatisfy(key -> {
                    assertThat(key.read().otherThread()).isTrue();
                    assertThat(key.read().property()).isEqualTo("notes");
                });
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
