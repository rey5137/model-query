package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.vendor.ChildStatements;
import com.rey.modelquery.jpa.vendor.HandOffObserver;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The executor's hand-off to the {@link HandOffObserver} (api/16 R-INS-18, D-228), over in-memory H2, with
 * {@link Recording} registered through {@code META-INF/services} in this module's test resources.
 */
class HandOffTest {

    record Item(Long id, Integer a, String note) {

        Item noted(String by) {
            return new Item(id, a, by);
        }
    }

    record Parent(Long id, Integer a, List<Item> items, String note) {

        Parent noted(String by) {
            return new Parent(id, a, items, by);
        }
    }

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Item, KeysetRowEntity, Long> ID =
            ColumnField.of(Item.class, ROOT, "id", Long.class);
    private static final ColumnField<Item, KeysetRowEntity, Integer> A =
            ColumnField.of(Item.class, ROOT, "a", Integer.class);

    private static final ColumnField<Parent, KeysetRowEntity, Long> PARENT_ID =
            ColumnField.of(Parent.class, ROOT, "id", Long.class);
    private static final ColumnField<Parent, KeysetRowEntity, Integer> PARENT_A =
            ColumnField.of(Parent.class, ROOT, "a", Integer.class);

    private static final ModelQuery.Builder<KeysetRowEntity, Long, Item> ITEM =
            ModelQuery.builder(ROOT, row -> new Item(row.get(ID), row.get(A), null)).select(SelectSet.of(ID, A))
                    .primaryKey(PrimaryKey.of(ID));

    /** No fetch plan: {@code list} maps it on the flat-row path. */
    private static final ModelQuery<KeysetRowEntity, Long, Item> ITEMS = ITEM.orderBy(ID.asc()).build();

    private static final ModelQuery<KeysetRowEntity, Long, Item> KEYSET_ITEMS = ITEM.orderBy(ID.asc()).keyset().build();

    /** Each parent row's child is the row itself, so every parent has one. */
    private static final ChildField<Parent, Item> CHILD = new ChildField<>() {
        @Override
        public String name() {
            return "items";
        }

        @Override
        public ColumnField<Parent, ?, ?> key() {
            return PARENT_ID;
        }

        @Override
        public Optional<ColumnField<Item, ?, ?>> foreignKey() {
            return Optional.of(ID);
        }

        @Override
        public Optional<TableField<?, ?>> through() {
            return Optional.empty();
        }

        @Override
        public boolean isToMany() {
            return false;
        }

        @Override
        public ModelQuery.Builder<?, ?, Item> query() {
            return ITEM;
        }

        @Override
        public Parent with(Parent parent, List<Item> children) {
            return new Parent(parent.id(), parent.a(), children, parent.note());
        }
    };

    /**
     * A child load whose own plan has an enricher, and a parent enricher: every model handed on is a copy the plan
     * made, never the one the mapper built.
     */
    private static final ModelQuery<KeysetRowEntity, Long, Parent> PARENTS = ModelQuery
            .builder(ROOT, row -> new Parent(row.get(PARENT_ID), row.get(PARENT_A), List.of(), null))
            .select(SelectSet.of(PARENT_ID, PARENT_A))
            .primaryKey(PrimaryKey.of(PARENT_ID))
            .orderBy(PARENT_ID.asc())
            .fetch(FetchPlan.of(SelectSet.of(PARENT_ID, PARENT_A))
                    .child(CHILD, FetchPlan.of(SelectSet.of(ID, A))
                            .enrich(Enricher.of(page -> page.stream().map(item -> item.noted("child")).toList(), ID)))
                    .enrich(Enricher.of(page -> page.stream().map(parent -> parent.noted("parent")).toList(),
                            PARENT_ID)))
            .build();

    private static SessionFactory sessions;

    @BeforeAll
    static void seed() {
        sessions = new Configuration().addAnnotatedClass(KeysetRowEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:hand-off;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .build());
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            for (long id = 1; id <= 3; id++) {
                em.persist(new KeysetRowEntity(id, (int) id * 10, null, 0));
            }
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @Test
    void r_ins_18_building_an_executor_looks_no_observer_up() {
        withExecutor(executor -> assertThat(executor.list(ITEMS, Limit.unlimited())).hasSize(3));

        // Whichever call constructed the one observer of this class loader, it was not an executor being built.
        assertThat(HandOffObserver.installed()).isInstanceOf(Recording.class);
        assertThat(Recording.constructedAt).isNotNull().noneMatch(frame ->
                frame.getMethodName().equals("<init>") && frame.getClassName().equals(
                        DefaultModelQueryExecutor.class.getName())
                        || frame.getMethodName().equals("create") && frame.getClassName().equals(
                        ModelQueryExecutor.class.getName()));
        assertThat(Recording.constructedAt).anyMatch(frame -> frame.getMethodName().equals("installed")
                && frame.getClassName().equals(HandOffObserver.class.getName()));
    }

    @Test
    void r_ins_18_every_read_hands_its_result_off_once_with_each_models_row() {
        Map<String, Function<ModelQueryExecutor<KeysetRowEntity>, List<Item>>> calls = new LinkedHashMap<>();
        calls.put("list (flat rows)", executor -> executor.list(ITEMS, Limit.unlimited()));
        calls.put("page", executor -> executor.page(ITEMS, PageSpec.of(0, 2), CountMode.COUNT).content());
        calls.put("keyset page", executor -> executor.page(KEYSET_ITEMS, KeysetSpec.first(2)).content());
        calls.put("one(query)", executor -> executor.one(ITEM.where(f -> f.eq(ID, 2L)).build()).stream().toList());
        calls.put("one(query, key)", executor -> executor.one(ITEMS, 3L).stream().toList());
        calls.put("first", executor -> executor.first(ITEMS).stream().toList());
        calls.put("byKeys", executor -> List.copyOf(executor.byKeys(ITEMS, List.of(3L, 1L)).values()));
        calls.forEach((name, call) -> {
            List<Item> result = new ArrayList<>();
            List<HandOff> handOffs = Recording.recording(() -> withExecutor(executor -> result.addAll(call.apply(
                    executor))));
            assertThat(result).as(name).isNotEmpty();
            assertThat(handOffs).as(name).singleElement().satisfies(handOff -> assertHandedOff(name, handOff,
                    ITEMS.toString(), result, ID));
        });
    }

    @Test
    void r_ins_18_an_export_hands_off_each_page_before_its_transformer_and_a_stream_each_model_before_its_body() {
        for (ModelQuery<KeysetRowEntity, Long, Item> q : List.of(ITEMS, KEYSET_ITEMS)) {
            List<Item> transformed = new ArrayList<>();
            List<Integer> handOffsBeforeEachPage = new ArrayList<>();
            List<HandOff> handOffs = new ArrayList<>();
            handOffs.addAll(Recording.recording(() -> withExecutor(executor -> executor.export(q,
                    ExportOptions.of(2), page -> {
                        handOffsBeforeEachPage.add(Recording.armed().size());
                        transformed.addAll(page);
                        return page;
                    }, item -> { }))));
            assertThat(transformed).hasSize(3);
            assertThat(handOffs).hasSize(2);
            assertThat(handOffsBeforeEachPage).containsExactly(1, 2);
            assertHandedOff("export", handOffs, transformed);
        }

        List<Item> streamed = new ArrayList<>();
        List<Integer> handOffsBeforeEachModel = new ArrayList<>();
        List<HandOff> handOffs = Recording.recording(() -> withExecutor(executor -> executor.stream(ITEMS,
                Limit.unlimited(), models -> {
                    models.forEach(item -> {
                        handOffsBeforeEachModel.add(Recording.armed().size());
                        streamed.add(item);
                    });
                    return null;
                })));
        assertThat(streamed).hasSize(3);
        assertThat(handOffsBeforeEachModel).containsExactly(1, 2, 3);
        assertHandedOff("stream", handOffs, streamed);
    }

    @Test
    void r_ins_18_a_fetch_plan_hands_off_its_with_copies_and_enricher_instances_and_children_from_their_own_rows() {
        List<Parent> result = new ArrayList<>();
        List<HandOff> handOffs = Recording.recording(() -> withExecutor(executor -> result.addAll(executor.list(
                PARENTS, Limit.unlimited()))));
        ChildLoad<Parent, ?> load = PARENTS.fetch().orElseThrow().childLoads().get(0);

        assertThat(result).hasSize(3).allSatisfy(parent -> {
            assertThat(parent.note()).isEqualTo("parent");
            assertThat(parent.items()).singleElement().satisfies(item -> assertThat(item.note()).isEqualTo("child"));
        });
        assertThat(handOffs).hasSize(2);
        HandOff children = handOffs.get(0);
        assertThat(children.child()).containsSame(load);
        assertThat(children.query()).isSameAs(load.query());
        List<Item> childModels = result.stream().map(parent -> parent.items().get(0)).toList();
        assertHandedOff("children", children, load.query().toString(), childModels, ID);
        HandOff parents = handOffs.get(1);
        assertThat(parents.child()).isEmpty();
        assertThat(parents.query()).isSameAs(PARENTS);
        assertHandedOff("parents", parents, PARENTS.toString(), result, PARENT_ID);
    }

    @Test
    void r_ins_18_an_empty_result_and_a_child_statements_run_hand_nothing_off() {
        List<HandOff> handOffs = Recording.recording(() -> withExecutor(executor -> {
            assertThat(executor.list(ITEM.where(f -> f.eq(ID, 99L)).build(), Limit.unlimited())).isEmpty();
            assertThat(executor.list(ITEMS, Limit.of(0))).isEmpty();
            ChildStatements.run(executor, PARENTS.fetch().orElseThrow().childLoads().get(0), List.of(1L, 2L));
        }));

        assertThat(handOffs).isEmpty();
    }

    @Test
    void r_ins_18_persist_with_a_returning_query_hands_off_the_model_it_returns() {
        ModelPersist<KeysetRowEntity, Long, Item> persist = ModelPersist.of(InsertColumns.<Item, KeysetRowEntity>of(
                ROOT).addKey(ID, Item::id).add(A, Item::a).add(ColumnField.of(Item.class, ROOT, "c", Integer.class),
                        item -> 0), Long.class, new Item(7L, 70, null));
        List<Item> result = new ArrayList<>();
        List<HandOff> handOffs = Recording.recording(() -> {
            try (EntityManager em = sessions.createEntityManager()) {
                em.getTransaction().begin();
                result.add(ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults())
                        .persist(persist, ITEM.build()));
                em.getTransaction().rollback();
            }
        });

        assertThat(result).containsExactly(new Item(7L, 70, null));
        assertThat(handOffs).singleElement().satisfies(handOff -> assertHandedOff("persist", handOff,
                ITEMS.toString(), result, ID));
    }

    /** {@code handOffs}' models, in order, are {@code result}'s instances, each with its own row. */
    private static void assertHandedOff(String name, List<HandOff> handOffs, List<Item> result) {
        assertThat(handOffs).as(name).allSatisfy(handOff -> {
            assertThat(handOff.child()).isEmpty();
            assertThat(handOff.models()).hasSameSizeAs(handOff.rows());
            assertRowsMatch(name, handOff, ID);
        });
        assertSameInstances(name, handOffs.stream().flatMap(handOff -> handOff.models().stream()).toList(), result);
    }

    private static <M> void assertHandedOff(String name, HandOff handOff, String query, List<M> result,
            ColumnField<M, ?, Long> id) {
        assertThat(handOff.query()).as(name).hasToString(query);
        assertSameInstances(name, handOff.models(), result);
        assertRowsMatch(name, handOff, id);
    }

    /** Each row, by position, holds its model's id. */
    private static <M> void assertRowsMatch(String name, HandOff handOff, ColumnField<M, ?, Long> id) {
        assertThat(handOff.rows()).as(name).hasSameSizeAs(handOff.models());
        for (int i = 0; i < handOff.models().size(); i++) {
            Object model = handOff.models().get(i);
            Long modelId = model instanceof Item item ? item.id() : ((Parent) model).id();
            assertThat(handOff.rows().get(i).get(id)).as(name + " row " + i).isEqualTo(modelId);
        }
    }

    private static void assertSameInstances(String name, List<?> handedOff, List<?> result) {
        assertThat(handedOff).as(name).hasSameSizeAs(result);
        for (int i = 0; i < result.size(); i++) {
            assertThat(handedOff.get(i)).as(name + " model " + i).isSameAs(result.get(i));
        }
    }

    private static void withExecutor(Consumer<ModelQueryExecutor<KeysetRowEntity>> work) {
        try (EntityManager em = sessions.createEntityManager()) {
            work.accept(ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults()));
        }
    }

    /** One hand-off, its lists copied. */
    record HandOff(ModelQuery<?, ?, ?> query, Optional<ChildLoad<?, ?>> child, List<Object> models, List<Row> rows) {}

    /**
     * The test observer: records the hand-offs of the thread inside {@link #recording}, and nothing elsewhere, so
     * the module's other tests are unaffected. Remembers where it was constructed, which is where it was looked up.
     */
    public static final class Recording implements HandOffObserver {

        static volatile List<StackTraceElement> constructedAt;

        private static final ThreadLocal<List<HandOff>> ARMED = new ThreadLocal<>();

        public Recording() {
            constructedAt = Arrays.asList(new Throwable().getStackTrace());
        }

        @Override
        public void handedOff(ModelQuery<?, ?, ?> query, Optional<ChildLoad<?, ?>> child, List<?> models,
                List<Row> rows) {
            List<HandOff> armed = ARMED.get();
            if (armed != null) {
                armed.add(new HandOff(query, child, new ArrayList<>(models), new ArrayList<>(rows)));
            }
        }

        /** The hand-offs recorded so far inside {@link #recording} on this thread. */
        static List<HandOff> armed() {
            return ARMED.get();
        }

        /** The hand-offs {@code work} made on this thread, in order. */
        static List<HandOff> recording(Runnable work) {
            List<HandOff> handOffs = new ArrayList<>();
            ARMED.set(handOffs);
            try {
                work.run();
            } finally {
                ARMED.remove();
            }
            return handOffs;
        }
    }
}
