package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.EntityJoinRenderer;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HibernateEntityJoinTest {

    @Entity(name = "EjRoot")
    static class RootRow {
        @Id
        long id;
    }

    @Entity(name = "EjFact")
    static class Fact {
        @Id
        long id;
        long a;
        long b;
        int number;
        String word;
    }

    @Entity(name = "EjPerson")
    static class Person {
        @Id
        long id;
        String label;
        @ManyToOne
        @JoinColumns({@JoinColumn(name = "code_a", referencedColumnName = "a"),
                @JoinColumn(name = "code_b", referencedColumnName = "b")})
        Code code;
        @OneToMany(mappedBy = "person")
        List<Child> children = new ArrayList<>();
    }

    @Entity(name = "EjChild")
    static class Child {
        @Id
        long id;
        @ManyToOne
        Person person;
    }

    public static class CodeId implements Serializable {
        public long a;
        public long b;
        public CodeId() {}
        @Override
        public boolean equals(Object other) {
            return other instanceof CodeId id && a == id.a && b == id.b;
        }
        @Override
        public int hashCode() { return Long.hashCode(a) * 31 + Long.hashCode(b); }
    }

    @Entity(name = "EjCode")
    @IdClass(CodeId.class)
    static class Code {
        @Id
        long a;
        @Id
        long b;
        String label;
    }

    @Embeddable
    public static class EmbeddedKey implements Serializable {
        long a;
        long b;
        public EmbeddedKey() {}
        @Override
        public boolean equals(Object other) {
            return other instanceof EmbeddedKey key && a == key.a && b == key.b;
        }
        @Override
        public int hashCode() { return Long.hashCode(a) * 31 + Long.hashCode(b); }
    }

    @Entity(name = "EjEmbedded")
    static class Embedded {
        @EmbeddedId
        EmbeddedKey key;
        String label;
    }

    @Entity(name = "EjAssociationId")
    static class AssociationId {
        @Id
        @ManyToOne
        Person person;
    }

    @Entity(name = "EjIntId")
    static class IntId {
        @Id
        int id;
        String label;
    }

    record Keys(Long rootId, Long a, Long b, String word, Integer number) {}
    record View(Long id, String label, Long count) {}

    private static final TableField<RootRow, RootRow> ROOT = TableField.root(RootRow.class);
    private static final TableField<Fact, Fact> FACT = TableField.root(Fact.class);
    private static final OrderedColumnField<View, RootRow, Long> ID =
            ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final ColumnField<Keys, Fact, Long> K_ID =
            ColumnField.of(Keys.class, FACT, "id", Long.class).named("rootId");
    private static final ColumnField<Keys, Fact, Long> K_A =
            ColumnField.of(Keys.class, FACT, "a", Long.class).named("a");
    private static final ColumnField<Keys, Fact, Long> K_B =
            ColumnField.of(Keys.class, FACT, "b", Long.class).named("b");
    private static final ColumnField<Keys, Fact, String> K_WORD =
            ColumnField.of(Keys.class, FACT, "word", String.class).named("word");
    private static final ColumnField<Keys, Fact, Integer> K_NUMBER =
            ColumnField.of(Keys.class, FACT, "number", Integer.class).named("number");
    private static final SourceQuery<Keys> SOURCE = SourceQuery.builder(Keys.class, FACT,
            SelectSet.of(K_ID, K_A, K_B, K_WORD, K_NUMBER), PrimaryKey.of(K_ID)).build();
    private static final SourceJoin<View, Keys> KEYS = SourceJoin.of("keys", Keys.class, JoinKind.LEFT,
            List.of(SourceJoin.on(ID, K_ID)));
    private static final ColumnField<View, Keys, Long> A = ColumnField.over(View.class, KEYS.root(), K_A);
    private static final ColumnField<View, Keys, Long> B = ColumnField.over(View.class, KEYS.root(), K_B);
    private static final ColumnField<View, Keys, String> WORD = ColumnField.over(View.class, KEYS.root(), K_WORD);
    private static final ColumnField<View, Keys, Integer> NUMBER =
            ColumnField.over(View.class, KEYS.root(), K_NUMBER);
    private static final List<String> SQL = new ArrayList<>();
    private static SessionFactory factory;

    @BeforeAll
    static void start() {
        factory = new Configuration().addAnnotatedClass(RootRow.class).addAnnotatedClass(Fact.class)
                .addAnnotatedClass(Person.class).addAnnotatedClass(Code.class).addAnnotatedClass(Embedded.class)
                .addAnnotatedClass(Child.class).addAnnotatedClass(AssociationId.class).addAnnotatedClass(IntId.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:entity-join")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .setStatementInspector(sql -> { SQL.add(sql); return sql; }).buildSessionFactory();
        factory.inTransaction(em -> {
            for (long id = 1; id <= 3; id++) {
                var root = new RootRow();
                root.id = id;
                em.persist(root);
            }
            for (long id = 1; id <= 2; id++) {
                var fact = new Fact();
                fact.id = id;
                fact.a = id == 1 ? 10 : 99;
                fact.b = 20;
                fact.word = "ten";
                fact.number = 10;
                em.persist(fact);
            }
            var code = new Code();
            code.a = 10;
            code.b = 20;
            code.label = "code";
            em.persist(code);
            var person = new Person();
            person.id = 10;
            person.label = "ten";
            person.code = code;
            em.persist(person);
            var embedded = new Embedded();
            embedded.key = new EmbeddedKey();
            embedded.key.a = 10;
            embedded.key.b = 20;
            embedded.label = "embedded";
            em.persist(embedded);
            var intId = new IntId();
            intId.id = 10;
            intId.label = "int";
            em.persist(intId);
        });
    }

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.close();
        }
    }

    private static EntityJoin<View, Person> person(JoinKind kind) {
        return EntityJoin.of("person", Person.class, kind, List.of(EntityJoin.on(A, "id")));
    }

    private static <E> ModelQuery.Builder<RootRow, Long, View> query(EntityJoin<View, E> join) {
        ColumnField<View, E, String> label = ColumnField.of(View.class, join.root(), "label", String.class);
        return ModelQuery.builder(ROOT, row -> new View(row.get(ID), row.get(label), null))
                .joinSource(KEYS, SOURCE).joinEntity(join).select(SelectSet.of(ID, label))
                .primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc());
    }

    private static void executing(Consumer<ModelQueryExecutor<RootRow>> work) {
        factory.inTransaction(em -> {
            SQL.clear();
            work.accept(ModelQueryExecutor.create(em, RootRow.class, ModelQueryConfig.defaults()));
        });
    }

    @Test
    void ac_src_53_left_join_reads_entity_and_preserves_unmatched_rows() {
        var query = query(person(JoinKind.LEFT)).build();
        executing(executor -> {
            assertThat(executor.list(query, Limit.unlimited())).containsExactly(
                    new View(1L, "ten", null), new View(2L, null, null), new View(3L, null, null));
            assertThat(executor.count(query)).isEqualTo(3);
        });
    }

    @Test
    void ac_src_54_inner_join_narrows_every_paging_and_export_statement() {
        var query = query(person(JoinKind.INNER)).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0L)).build();
        executing(executor -> {
            assertThat(executor.page(query, PageSpec.of(0, 2), CountMode.COUNT).total()).hasValue(1L);
            assertThat(executor.page(query, PageSpec.ofOffset(1, 1), CountMode.NO_COUNT).content()).isEmpty();
            assertThat(executor.byKeys(query, List.of(1L, 2L, 3L))).containsOnlyKeys(1L);
            var ids = new ArrayList<Long>();
            executor.export(query, ExportOptions.of(1), page -> page, view -> ids.add(view.id()));
            assertThat(ids).containsExactly(1L);
            assertThat(SQL).allSatisfy(sql -> assertThat(sql).contains("join EjPerson"));
        });
    }

    @Test
    void ac_src_55_composite_ids_join_on_every_attribute() {
        var code = EntityJoin.of("code", Code.class, JoinKind.LEFT,
                List.of(EntityJoin.on(A, "a"), EntityJoin.on(B, "b")));
        var embedded = EntityJoin.of("embedded", Embedded.class, JoinKind.LEFT,
                List.of(EntityJoin.on(A, "key.a"), EntityJoin.on(B, "key.b")));
        executing(executor -> {
            assertThat(executor.list(query(code).build(), Limit.unlimited())).extracting(View::label)
                    .containsExactly("code", null, null);
            assertThat(executor.list(query(embedded).build(), Limit.unlimited())).extracting(View::label)
                    .containsExactly("embedded", null, null);
        });
    }

    @Test
    void ac_src_55_entity_and_descendant_counts_use_first_basic_id_even_when_filtered() {
        var person = person(JoinKind.LEFT);
        TableField<Person, Code> code = TableField.join(person.root(), "code", JoinType.LEFT);
        var embedded = EntityJoin.of("embedded", Embedded.class, JoinKind.LEFT,
                List.of(EntityJoin.on(A, "key.a"), EntityJoin.on(B, "key.b")));
        executing(executor -> {
            for (var table : List.of(person.root(), code)) {
                for (var count : List.of(Agg.<View>count(table),
                        Agg.<View>count(table).filter(f -> f.gt(ID, 0L)))) {
                    var grouped = ModelQuery.builder(ROOT, row -> new View(row.get(ID), null, row.get(count)))
                            .joinSource(KEYS, SOURCE).joinEntity(person).select(SelectSet.of(ID, count))
                            .groupBy(SelectSet.of(ID)).orderBy(ID.asc()).build();
                    assertThat(executor.list(grouped, Limit.unlimited())).extracting(View::count)
                            .containsExactly(1L, 0L, 0L);
                    assertThat(executor.count(grouped)).isEqualTo(3L);
                }
            }
            var count = Agg.<View>count(embedded.root());
            var grouped = ModelQuery.builder(ROOT, row -> new View(row.get(ID), null, row.get(count)))
                    .joinSource(KEYS, SOURCE).joinEntity(embedded).select(SelectSet.of(ID, count))
                    .groupBy(SelectSet.of(ID)).orderBy(ID.asc()).build();
            assertThat(executor.list(grouped, Limit.unlimited())).extracting(View::count)
                    .containsExactly(1L, 0L, 0L);
            assertThat(SQL).noneSatisfy(sql -> assertThat(sql).contains("count(("))
                    .noneSatisfy(sql -> assertThat(sql).contains("count(*)"));
        });
    }

    @Test
    void ac_src_56_id_pair_errors_fail_on_resolution_before_sql() {
        List<EntityJoin<View, ?>> invalid = List.of(
                EntityJoin.of("partial", Code.class, JoinKind.LEFT, List.of(EntityJoin.on(A, "a"))),
                EntityJoin.of("extra", Person.class, JoinKind.LEFT,
                        List.of(EntityJoin.on(A, "id"), EntityJoin.on(WORD, "label"))),
                EntityJoin.of("nonId", Person.class, JoinKind.LEFT, List.of(EntityJoin.on(WORD, "label"))),
                EntityJoin.of("wrongType", Person.class, JoinKind.LEFT, List.of(EntityJoin.on(WORD, "id"))),
                EntityJoin.of("association", AssociationId.class, JoinKind.LEFT,
                        List.of(EntityJoin.on(A, "person"))),
                EntityJoin.of("duplicate", Code.class, JoinKind.LEFT,
                        List.of(EntityJoin.on(A, "a"), EntityJoin.on(B, "a"))));
        executing(executor -> {
            for (var join : invalid) {
                var definition = ModelQuery.builder(ROOT, row -> new View(row.get(ID), null, null))
                        .joinSource(KEYS, SOURCE).joinEntity(join).select(SelectSet.of(ID)).build();
                assertThatThrownBy(() -> executor.list(definition, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1911));
                assertThat(SQL).isEmpty();
            }
        });
    }

    @Test
    void ac_src_56_primitive_id_accepts_the_boxed_anchor_type() {
        EntityJoin<View, IntId> joined = EntityJoin.of("intId", IntId.class, JoinKind.LEFT,
                List.of(EntityJoin.on(NUMBER, "id")));
        executing(executor -> assertThat(executor.list(query(joined).build(), Limit.unlimited()))
                .extracting(View::label).containsExactly("int", "int", null));
    }

    @Test
    void ac_src_56_an_unregistered_entity_column_is_mq1003_before_sql() {
        var label = ColumnField.of(View.class, person(JoinKind.LEFT).root(), "label", String.class);
        var definition = ModelQuery.builder(ROOT, row -> new View(row.get(ID), row.get(label), null))
                .joinSource(KEYS, SOURCE).select(SelectSet.of(ID, label)).build();
        executing(executor -> {
            assertThatThrownBy(() -> executor.list(definition, Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
                        assertThat(e.code()).isEqualTo(MqCode.MQ1003);
                        assertThat(e).hasMessageContaining("person");
                    });
            assertThat(SQL).isEmpty();
        });
    }

    @Test
    void ac_src_56_to_many_below_entity_join_is_refused_before_sql() {
        var person = person(JoinKind.LEFT);
        TableField<Person, Child> children = TableField.join(person.root(), "children", JoinType.LEFT);
        var childId = ColumnField.of(View.class, children, "id", Long.class);
        var query = ModelQuery.builder(ROOT, row -> new View(row.get(ID), null, null))
                .joinSource(KEYS, SOURCE).joinEntity(person).select(SelectSet.of(ID, childId)).build();
        executing(executor -> {
            assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1911));
            assertThat(SQL).isEmpty();
        });
    }

    @Test
    void ac_src_56_exists_does_not_allow_to_many_below_entity_join() {
        var person = person(JoinKind.LEFT);
        TableField<Person, Child> children = TableField.join(person.root(), "children", JoinType.LEFT);
        var query = query(person).where(f -> f.exists(children)).build();
        executing(executor -> {
            assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1911));
            assertThat(SQL).isEmpty();
        });
    }

    @Test
    void ac_src_55_inner_entity_and_inner_descendant_still_count_the_id() {
        var person = person(JoinKind.INNER);
        TableField<Person, Code> code = TableField.join(person.root(), "code", JoinType.INNER);
        executing(executor -> {
            for (var table : List.of(person.root(), code)) {
                var count = Agg.<View>count(table);
                var query = ModelQuery.builder(ROOT, row -> new View(null, null, row.get(count)))
                        .joinSource(KEYS, SOURCE).joinEntity(person).select(SelectSet.of(count)).build();
                assertThat(executor.list(query, Limit.unlimited())).extracting(View::count).containsExactly(1L);
            }
            assertThat(SQL).hasSize(2).allSatisfy(sql -> assertThat(sql)
                    .containsPattern("count\\(\\w+\\.(id|a)\\)").doesNotContain("count(*)")
                    .doesNotContain("count(1)").doesNotContain("count(("));
        });
    }

    @Test
    void ac_src_58_renderer_is_registered_root_anchored_and_builds_one_predicate() {
        assertThat(new HibernateProviderSupport().entityJoins()).contains(HibernateEntityJoinRenderer.INSTANCE);
        assertThat(HibernateEntityJoinRenderer.EntityMethods.of(JpaFrom.class)).isPresent();
        assertThat(HibernateEntityJoinRenderer.EntityMethods.of(Object.class)).isEmpty();
        assertThat(HibernateEntityJoinRenderer.EntityMethods.of(JoinWithoutOn.class)).isEmpty();
        factory.inTransaction(em -> {
            var cb = em.getCriteriaBuilder();
            var statement = cb.createTupleQuery();
            var root = statement.from(RootRow.class);
            AtomicInteger predicates = new AtomicInteger();
            var joined = HibernateEntityJoinRenderer.INSTANCE.join(root, Person.class, JoinKind.LEFT, entity -> {
                predicates.incrementAndGet();
                return cb.equal(entity.get("id"), root.get("id"));
            });
            assertThat(predicates).hasValue(1);
            assertThat(joined).isNotInstanceOf(Root.class);
            assertThatThrownBy(() -> HibernateEntityJoinRenderer.INSTANCE.join(joined, Person.class, JoinKind.LEFT,
                    entity -> cb.conjunction())).isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
            assertThatThrownBy(() -> new HibernateEntityJoinRenderer(Optional.empty())
                    .join(root, Person.class, JoinKind.LEFT, entity -> cb.conjunction()))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
        });
    }

    public static class JoinWithoutOn {
        public Object join(Class<?> entity, SqmJoinType kind) { return null; }
    }

    @Test
    void ac_src_58_entity_renderer_calls_on_exactly_once() {
        factory.inTransaction(em -> {
            var methods = HibernateEntityJoinRenderer.EntityMethods.of(JpaFrom.class).orElseThrow();
            Predicate predicate = em.getCriteriaBuilder().conjunction();
            AtomicInteger calls = new AtomicInteger();
            var joined = (From<?, ?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {methods.join().getReturnType()}, (proxy, method, args) -> {
                        assertThat(method.getName()).isEqualTo("on");
                        assertThat((Predicate[]) args[0]).containsExactly(predicate);
                        calls.incrementAndGet();
                        return proxy;
                    });
            var root = (Root<?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {JpaRoot.class}, (proxy, method, args) -> {
                        assertThat(method.getName()).isEqualTo("join");
                        assertThat(args).containsExactly(Person.class, SqmJoinType.LEFT);
                        return joined;
                    });
            assertThat(HibernateEntityJoinRenderer.INSTANCE.join(root, Person.class, JoinKind.LEFT,
                    entity -> predicate)).isSameAs(joined);
            assertThat(calls).hasValue(1);
        });
    }

    @Test
    void ac_src_58_joined_entity_metadata_falls_back_to_the_factory_metamodel() {
        factory.inTransaction(em -> {
            EntityJoinRenderer renderer = new EntityJoinRenderer() {
                @Override
                public <E> From<?, E> join(From<?, ?> root, Class<E> entity, JoinKind kind,
                        Function<From<?, E>, Predicate> on) {
                    return withoutEntityModel(HibernateEntityJoinRenderer.INSTANCE.join(root, entity, kind,
                            joined -> on.apply(withoutEntityModel(joined))));
                }
            };
            var options = RenderOptions.portable().withSourceRenderer(HibernateSourceRenderer.INSTANCE)
                    .withEntityJoinRenderer(renderer);
            var query = query(person(JoinKind.LEFT)).build();
            var built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL,
                    options.withMetamodel(em.getMetamodel()));
            assertThat(em.createQuery(built.query()).getResultList()).hasSize(3);
            assertThatThrownBy(() -> query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
        });
    }

    @SuppressWarnings("unchecked")
    private static <E> From<?, E> withoutEntityModel(From<?, E> joined) {
        return (From<?, E>) Proxy.newProxyInstance(HibernateEntityJoinTest.class.getClassLoader(),
                new Class<?>[] {From.class}, (proxy, method, args) ->
                        method.getName().equals("getModel") ? null : method.invoke(joined, args));
    }

    @Test
    void ac_src_58_source_renderer_failure_precedes_missing_entity_hook() {
        factory.inTransaction(em -> {
            var query = query(person(JoinKind.LEFT)).build();
            assertThatThrownBy(() -> query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, RenderOptions.portable()))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class, e -> {
                        assertThat(e.code()).isEqualTo(MqCode.MQ4014);
                        assertThat(e).hasMessageContaining("ProviderSupport#sources()");
                    });
            assertThatThrownBy(() -> query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, RenderOptions.portable()
                    .withSourceRenderer(new HibernateSourceRenderer(Optional.empty()))))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class, e -> {
                        assertThat(e.code()).isEqualTo(MqCode.MQ4014);
                        assertThat(e).hasMessageContaining("joined sources");
                    });
            assertThatThrownBy(() -> query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, RenderOptions.portable()
                    .withSourceRenderer(HibernateSourceRenderer.INSTANCE)))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class, e -> {
                        assertThat(e.code()).isEqualTo(MqCode.MQ4014);
                        assertThat(e).hasMessageContaining("ProviderSupport#entityJoins()");
                    });
        });
    }

    @Test
    void ac_src_58_vendor_cte_gate_precedes_provider_hooks() {
        VendorProfile unsupported = new VendorProfile() {
            @Override
        public DatabaseVendor vendor() { return DatabaseVendor.H2; }
            @Override
        public int maxInListSize() { return 1000; }
            @Override
        public int maxBindParameters() { return 1000; }
            @Override
        public NullOrdering defaultAscendingNullOrdering() { return NullOrdering.NULLS_FIRST; }
            @Override
        public void applyTimeout(jakarta.persistence.Query query, java.time.Duration timeout) {
                query.setHint("jakarta.persistence.query.timeout", Math.toIntExact(timeout.toMillis()));
            }
        };
        factory.inTransaction(em -> {
            SQL.clear();
            var executor = ModelQueryExecutor.create(em, RootRow.class,
                    ModelQueryConfig.defaults().vendorProfiles(List.of(unsupported)));
            assertThatThrownBy(() -> executor.list(query(person(JoinKind.LEFT)).build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015));
            assertThat(SQL).isEmpty();
        });
    }
}
