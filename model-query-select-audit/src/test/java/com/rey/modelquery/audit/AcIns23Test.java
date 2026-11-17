package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ForwardingModelQueryExecutor;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.example.audit.AuditCaller;
import org.junit.jupiter.api.Test;

/**
 * AC-INS-23: Every {@link ModelQueryExecutor} method returning models, enumerated by reflection, tags its result;
 * a {@code stream} call and a call through an application decorator get the application's call site.
 */
class AcIns23Test extends AuditTestBase {

    @Test
    void ac_ins_23_stream_call_gets_application_call_site() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .orderBy(QAuditOrderView.ID.asc())
                    .build();

            List<AuditOrderView> result = AuditCaller.streamCall(() ->
                    executor.stream(query, Limit.of(2), stream -> stream.toList()));
            assertThat(result).hasSize(2);
            for (AuditOrderView order : result) {
                Tag tag = AuditObserver.tagOf(order);
                assertThat(tag).isNotNull();
                assertThat(tag.callSite()).startsWith("AuditCaller.streamCall");
                assertThat(tag.method()).isEqualTo("stream");
            }
        }
    }

    @Test
    void ac_ins_23_call_through_application_decorator_gets_application_call_site() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> real = orderExecutor(em);
            ModelQueryExecutor<AuditOrderEntity> decorated = new AppDecorator(real);

            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            AuditOrderView order = AuditCaller.appDecoratorCall(() -> decorated.one(query)).orElseThrow();
            Tag tag = AuditObserver.tagOf(order);
            assertThat(tag).isNotNull();
            assertThat(tag.callSite()).startsWith("AuditCaller.appDecoratorCall");
            assertThat(tag.method()).isEqualTo("one");
        }
    }

    @Test
    void ac_ins_23_call_through_jdk_proxy_decorator_gets_application_call_site() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> real = orderExecutor(em);
            @SuppressWarnings("unchecked")
            ModelQueryExecutor<AuditOrderEntity> proxy = (ModelQueryExecutor<AuditOrderEntity>) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {ModelQueryExecutor.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                            return m.invoke(real, args);
                        }
                    });

            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            AuditOrderView order = AuditCaller.proxyDecoratorCall(() -> proxy.one(query)).orElseThrow();
            Tag tag = AuditObserver.tagOf(order);
            assertThat(tag).isNotNull();
            assertThat(tag.callSite()).startsWith("AuditCaller.proxyDecoratorCall");
            assertThat(tag.method()).isEqualTo("one");
        }
    }

    @Test
    void ac_ins_23_every_executor_method_returning_models_tags_its_result() throws Exception {
        Set<Method> testedMethods = new HashSet<>();

        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            ModelQueryExecutor<AuditOrderEntity> exec = orderExecutor(em);

            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .orderBy(QAuditOrderView.ID.asc())
                    .build();
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> keysetQuery = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .orderBy(QAuditOrderView.ID.asc())
                    .keyset()
                    .build();
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> returning = QAuditOrderView.query()
                    .select(SelectSet.of(QAuditOrderView.ID, QAuditOrderView.TOTAL))
                    .build();
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> singleQuery = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            // 1. list(query, limit)
            List<AuditOrderView> list1 = AuditCaller.executorCall(() -> exec.list(query, Limit.unlimited()));
            assertThat(list1).isNotEmpty();
            assertTagged("list", list1);
            testedMethods.add(ModelQueryExecutor.class.getMethod("list", ModelQuery.class, Limit.class));

            // 2. list(query, limit) with limit
            List<AuditOrderView> list2 = AuditCaller.executorCall(() -> exec.list(query, Limit.of(1)));
            assertThat(list2).isNotEmpty();
            assertTagged("list", list2);

            // 3. page(query, pageSpec, countMode)
            List<AuditOrderView> page1 = AuditCaller.executorCall(() ->
                    exec.page(query, PageSpec.of(0, 2), CountMode.COUNT)).content();
            assertThat(page1).isNotEmpty();
            assertTagged("page", page1);
            testedMethods.add(ModelQueryExecutor.class.getMethod("page",
                    ModelQuery.class, PageSpec.class, CountMode.class));

            // 4. page(query, pageSpec, countMode) with INLINE_COUNT
            List<AuditOrderView> page2 = AuditCaller.executorCall(() ->
                    exec.page(query, PageSpec.of(0, 2), CountMode.INLINE_COUNT)).content();
            assertThat(page2).isNotEmpty();
            assertTagged("page", page2);

            // 5. page(query, keysetSpec)
            List<AuditOrderView> page3 = AuditCaller.executorCall(() ->
                    exec.page(keysetQuery, KeysetSpec.first(2))).content();
            assertThat(page3).isNotEmpty();
            assertTagged("page", page3);
            testedMethods.add(ModelQueryExecutor.class.getMethod("page", ModelQuery.class, KeysetSpec.class));

            // 6. one(query)
            Optional<AuditOrderView> one1 = AuditCaller.executorCall(() -> exec.one(singleQuery));
            assertThat(one1).isPresent();
            assertTagged("one", List.of(one1.get()));
            testedMethods.add(ModelQueryExecutor.class.getMethod("one", ModelQuery.class));

            // 7. one(query, key)
            Optional<AuditOrderView> one2 = AuditCaller.executorCall(() -> exec.one(query, 1L));
            assertThat(one2).isPresent();
            assertTagged("one", List.of(one2.get()));
            testedMethods.add(ModelQueryExecutor.class.getMethod("one", ModelQuery.class, Object.class));

            // 8. first(query)
            Optional<AuditOrderView> first = AuditCaller.executorCall(() -> exec.first(query));
            assertThat(first).isPresent();
            assertTagged("first", List.of(first.get()));
            testedMethods.add(ModelQueryExecutor.class.getMethod("first", ModelQuery.class));

            // 9. byKeys(query, keys)
            Map<Long, AuditOrderView> byKeys = AuditCaller.executorCall(() -> exec.byKeys(query, List.of(1L, 2L)));
            assertThat(byKeys).isNotEmpty();
            assertTagged("byKeys", byKeys.values());
            testedMethods.add(ModelQueryExecutor.class.getMethod("byKeys", ModelQuery.class, Collection.class));

            // 10. stream(query, limit, body)
            List<AuditOrderView> streamed = AuditCaller.executorCall(() ->
                    exec.stream(query, Limit.of(2), s -> s.toList()));
            assertThat(streamed).isNotEmpty();
            assertTagged("stream", streamed);
            testedMethods.add(ModelQueryExecutor.class.getMethod("stream",
                    ModelQuery.class, Limit.class, Function.class));

            // 11. export(query, options, pageTransformer, sink)
            List<AuditOrderView> exported = new ArrayList<>();
            AuditCaller.executorCall(() -> {
                exec.export(query, ExportOptions.defaults(), Function.identity(), exported::add);
                return null;
            });
            assertThat(exported).isNotEmpty();
            assertTagged("export", exported);
            testedMethods.add(ModelQueryExecutor.class.getMethod("export",
                    ModelQuery.class, ExportOptions.class, Function.class, java.util.function.Consumer.class));

            // 12. persist(persist, returning)
            var insertCols = InsertColumns.<AuditOrderView, AuditOrderEntity>of(QAuditOrderView.ROOT)
                    .addKey(QAuditOrderView.ID, AuditOrderView::id)
                    .add(QAuditOrderView.TOTAL, AuditOrderView::total);
            var persist = ModelPersist.of(insertCols, Long.class,
                    new AuditOrderView(99L, 999L, "Persisted", Optional.empty(), List.of()));
            AuditOrderView persisted = AuditCaller.executorCall(() -> exec.persist(persist, returning));
            assertThat(persisted).isNotNull();
            assertTagged("persist", List.of(persisted));
            testedMethods.add(ModelQueryExecutor.class.getMethod("persist", ModelPersist.class, ModelQuery.class));

            // 13. updateReturning(update, returning)
            var update = ModelUpdate.builder(QAuditOrderView.ROOT)
                    .primaryKey(PrimaryKey.of(QAuditOrderView.ID))
                    .set(QAuditOrderView.TOTAL, 111L)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();
            List<AuditOrderView> updated = AuditCaller.executorCall(() -> exec.updateReturning(update, returning));
            assertThat(updated).isNotEmpty();
            assertTagged("updateReturning", updated);
            testedMethods.add(ModelQueryExecutor.class.getMethod("updateReturning",
                    ModelUpdate.class, ModelQuery.class));

            // 14. deleteReturning(delete, returning)
            var delete = ModelDelete.builder(QAuditOrderView.ROOT)
                    .primaryKey(PrimaryKey.of(QAuditOrderView.ID))
                    .where(f -> f.eq(QAuditOrderView.ID, 2L))
                    .build();
            List<AuditOrderView> deleted = AuditCaller.executorCall(() -> exec.deleteReturning(delete, returning));
            assertThat(deleted).isNotEmpty();
            assertTagged("deleteReturning", deleted);
            testedMethods.add(ModelQueryExecutor.class.getMethod("deleteReturning",
                    ModelDelete.class, ModelQuery.class));

            // 15. insertReturning(insert, returning)
            var insert = ValuesInsert.builder(insertCols, Long.class,
                    List.of(new AuditOrderView(88L, 888L, "Inserted", Optional.empty(), List.of()))).build();
            List<AuditOrderView> inserted = AuditCaller.executorCall(() -> exec.insertReturning(insert, returning));
            assertThat(inserted).isNotEmpty();
            assertTagged("insertReturning", inserted);
            testedMethods.add(ModelQueryExecutor.class.getMethod("insertReturning",
                    com.rey.modelquery.core.ModelInsert.class, ModelQuery.class));

            em.getTransaction().rollback();
        }

        assertCovered(ModelQueryExecutor.class, testedMethods, Map.of());
    }

    private static void assertCovered(Class<?> executor, Set<Method> exercised, Map<Method, String> excluded) {
        excluded.forEach((method, reason) -> assertThat(reason).as(method.toGenericString()).isNotBlank());
        for (Method method : executor.getMethods()) {
            if (returnsModels(method) && !excluded.containsKey(method)) {
                assertThat(exercised).as("model-returning method " + method.toGenericString()).contains(method);
            }
        }
    }

    private interface FutureExecutor extends ModelQueryExecutor<AuditOrderEntity> {
        <M> List<M> listAfter(ModelQuery<AuditOrderEntity, ?, M> query);
    }

    @Test
    void ac_ins_23_coverage_detects_new_methods_and_missing_overloads() throws Exception {
        Set<Method> covered = java.util.Arrays.stream(ModelQueryExecutor.class.getMethods())
                .filter(AcIns23Test::returnsModels).collect(java.util.stream.Collectors.toSet());
        assertThatThrownBy(() -> assertCovered(FutureExecutor.class, covered, Map.of()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("listAfter");
        Method keyed = ModelQueryExecutor.class.getMethod("one", ModelQuery.class, Object.class);
        covered.remove(keyed);
        assertThatThrownBy(() -> assertCovered(ModelQueryExecutor.class, covered, Map.of()))
                .isInstanceOf(AssertionError.class).hasMessageContaining(keyed.toGenericString());
        assertCovered(ModelQueryExecutor.class, covered, Map.of(keyed, "explicit exclusion in checker regression"));
    }

    private static boolean returnsModels(Method m) {
        return !m.getName().equals("count")
                && java.util.Arrays.asList(m.getParameterTypes()).contains(ModelQuery.class);
    }

    private static void assertTagged(String method, Collection<AuditOrderView> models) {
        for (AuditOrderView m : models) {
            Tag tag = AuditObserver.tagOf(m);
            assertThat(tag).as("tag for model from " + method).isNotNull();
            assertThat(tag.callSite()).startsWith("AuditCaller.executorCall");
        }
    }

    private static class AppDecorator extends ForwardingModelQueryExecutor<AuditOrderEntity> {
        AppDecorator(ModelQueryExecutor<AuditOrderEntity> delegate) {
            super(delegate);
        }
    }
}
