package com.rey.modelquery.tck.flt;

import static com.rey.modelquery.core.RenderOptions.portable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.flt.FiltersTest.Case;
import com.rey.modelquery.tck.flt.FiltersTest.Fixture;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.hibernate.SessionFactory;

/**
 * The write/read parity check (spec api/14 AC-WRT-07, R-WRT-10, R-WRT-11, R-WRT-17): for every fixture of the Filters
 * group, the rows an update writes, and a delete where no table references the root, are the distinct rows the
 * matching read returns, on each write path. A fixture whose every filter is skipped reads every row and its write
 * throws {@code MQ1601} (R-WRT-12). Every write runs in a transaction that is rolled back.
 */
class FilterWriteParityTest {

    /** The write paths. */
    enum WritePath {
        /** The built-in profile: one statement where the database reads its target in a sub-query, else key-first. */
        BUILT_IN,
        /** A profile that cannot read the target table in a sub-query: key-first wherever the write reads it. */
        KEY_FIRST,
        /** The built-in profile, in chunks of {@link #CHUNK} keys. */
        CHUNKED
    }

    private static final String EMPTY_FORM = ", empty Optional";

    /** Small enough that most fixtures write in several chunks. */
    private static final int CHUNK = 500;

    /**
     * A root the fixtures write: how to read its fixtures' rows, an update marking them, a delete when no table
     * references the root, the JPQL of the marked ids, and the rows the fixtures need inserted first.
     */
    private record Target<E, V>(Class<E> entity, ModelQuery.Builder<E, Object, V> read, Function<V, Long> id,
            ModelUpdate.Builder<E, Long, V> update, ModelDelete.Builder<E, Long, V> delete, String marked,
            Consumer<EntityManager> setup) {}

    // ---- FiltersTest's roots

    private static final ColumnField<FiltersTest.I, OrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(FiltersTest.I.class, FiltersTest.ITEMS, "quantity", Integer.class);

    private static final Target<OrderEntity, FiltersTest.O> ORDERS = new Target<>(OrderEntity.class,
            FiltersTest.ORDER_QUERY, FiltersTest.O::id,
            ModelUpdate.builder(FiltersTest.ORDERS).primaryKey(PrimaryKey.of(FiltersTest.ID))
                    .set(FiltersTest.STATUS, "MARKED"),
            null, "select o.id from OrderEntity o where o.status = 'MARKED'", em -> {});

    private static final Target<OrderItemEntity, FiltersTest.I> ITEMS = new Target<>(OrderItemEntity.class,
            FiltersTest.ITEM_QUERY, FiltersTest.I::id,
            ModelUpdate.builder(FiltersTest.ITEMS).primaryKey(PrimaryKey.of(FiltersTest.ITEM_ID))
                    .set(ITEM_QUANTITY, -1),
            ModelDelete.builder(FiltersTest.ITEMS).primaryKey(PrimaryKey.of(FiltersTest.ITEM_ID)),
            "select i.id from OrderItemEntity i where i.quantity = -1", em -> {});

    private static final Target<NullableSortEntity, FiltersTest.N> NULLABLE = new Target<>(NullableSortEntity.class,
            FiltersTest.NULLABLE_QUERY, FiltersTest.N::id,
            ModelUpdate.builder(FiltersTest.NULLABLE).primaryKey(PrimaryKey.of(FiltersTest.N_ID))
                    .set(FiltersTest.N_INT, -1),
            ModelDelete.builder(FiltersTest.NULLABLE).primaryKey(PrimaryKey.of(FiltersTest.N_ID)),
            "select n.id from NullableSortEntity n where n.sortInt = -1", FiltersTest::insertLikeRows);

    // ---- FilterCompositionTest's roots, each with the rows its LEFT-join and notExists fixtures need

    private static final Target<OrderEntity, FilterCompositionTest.O> COMPOSED_ORDERS = new Target<>(
            OrderEntity.class, FilterCompositionTest.ORDER_QUERY, FilterCompositionTest.O::id,
            ModelUpdate.builder(FilterCompositionTest.ORDERS).primaryKey(PrimaryKey.of(FilterCompositionTest.ID))
                    .set(FilterCompositionTest.STATUS, "MARKED"),
            null, ORDERS.marked(), FilterCompositionTest::insertLeftJoinRows);

    private static final Target<CustomerEntity, FilterCompositionTest.C> COMPOSED_CUSTOMERS = new Target<>(
            CustomerEntity.class, FilterCompositionTest.CUSTOMER_QUERY, FilterCompositionTest.C::id,
            ModelUpdate.builder(FilterCompositionTest.CUSTOMERS)
                    .primaryKey(PrimaryKey.of(FilterCompositionTest.C_ID)).set(FilterCompositionTest.C_NAME, "MARKED"),
            null, "select c.id from CustomerEntity c where c.name = 'MARKED'",
            FilterCompositionTest::insertLeftJoinRows);

    private static final Target<NullableSortEntity, FilterCompositionTest.N> COMPOSED_NULLABLE = new Target<>(
            NullableSortEntity.class, FilterCompositionTest.NULLABLE_QUERY, FilterCompositionTest.N::id,
            ModelUpdate.builder(FilterCompositionTest.NULLABLE).primaryKey(PrimaryKey.of(FilterCompositionTest.N_ID))
                    .set(FilterCompositionTest.N_INT, -1),
            ModelDelete.builder(FilterCompositionTest.NULLABLE)
                    .primaryKey(PrimaryKey.of(FilterCompositionTest.N_ID)),
            NULLABLE.marked(), em -> {});

    // ---- AC-WRT-07

    @TckTest
    void ac_wrt_07_every_filters_operator_fixture_writes_the_rows_its_read_returns_on_every_path(TckDatabase db) {
        var orders = fixtures(FiltersTest.orderCases(), FiltersTest.orderFixtures());
        var items = fixtures(FiltersTest.itemCases(), List.of());
        var nullable = fixtures(FiltersTest.nullableCases(), FiltersTest.nullableFixtures());
        try (Writes writes = new Writes(db)) {
            // Exactly the empty Optional forms are refused; notIn(col, List.of()) is a predicate (D-64).
            assertThat(writes.check(ORDERS, orders)).isEqualTo(emptyForms(orders));
            assertThat(writes.check(ITEMS, items)).isEmpty();
            assertThat(writes.check(NULLABLE, nullable)).isEqualTo(emptyForms(nullable));
        }
    }

    @TckTest
    void ac_wrt_07_every_filter_composition_fixture_writes_the_rows_its_read_returns_on_every_path(
            TckDatabase db) {
        try (Writes writes = new Writes(db)) {
            assertThat(writes.check(COMPOSED_ORDERS, FilterCompositionTest.orderFixtures()))
                    .containsExactly("not, skipped", "when false", "exists, skipped");
            assertThat(writes.check(COMPOSED_CUSTOMERS, FilterCompositionTest.customerFixtures())).isEmpty();
            assertThat(writes.check(COMPOSED_NULLABLE, FilterCompositionTest.nullableFixtures())).isEmpty();
        }
    }

    // ---- AC-FLT-19

    @TckTest
    void ac_flt_19_a_bulk_update_and_delete_over_an_expression_write_the_rows_the_read_returns(TckDatabase db) {
        // The read, the update and the delete all compare the same expression, on every write path (R-FLT-18).
        var overAnExpression = new Fixture<FiltersTest.I>("compare an expression",
                f -> f.gt(Expr.plus(ITEM_QUANTITY, 2), 5));
        try (Writes writes = new Writes(db)) {
            assertThat(writes.check(ITEMS, List.of(overAnExpression))).isEmpty();
        }
    }

    /** Each operator case's value form, and its empty {@code Optional} form where it has one, then {@code more}. */
    private static <V> List<Fixture<V>> fixtures(List<Case<V>> cases, List<Fixture<V>> more) {
        var fixtures = new ArrayList<Fixture<V>>();
        for (Case<V> c : cases) {
            fixtures.add(new Fixture<>(c.name(), c.value()));
            if (c.empty() != null) {
                fixtures.add(new Fixture<>(c.name() + EMPTY_FORM, c.empty()));
            }
        }
        fixtures.addAll(more);
        return fixtures;
    }

    private static List<String> emptyForms(List<? extends Fixture<?>> fixtures) {
        return fixtures.stream().map(Fixture::name).filter(name -> name.endsWith(EMPTY_FORM)).toList();
    }

    /** One session factory over {@code db} whose statements are recorded, and the built-in profile of its vendor. */
    private static final class Writes implements AutoCloseable {

        private final List<String> statements = new ArrayList<>();
        private final SessionFactory factory;
        private final VendorProfile builtIn;
        private final ModelQueryConfig keyFirst;

        Writes(TckDatabase db) {
            DataSource recording = ProxyDataSourceBuilder.create(JoinTestSupport.dataSource(db))
                    .afterQuery((exec, queries) -> queries.forEach(q ->
                            statements.add(q.getQuery().strip().toLowerCase(Locale.ROOT))))
                    .build();
            factory = JoinTestSupport.sessionFactory(recording);
            builtIn = VendorResolver.resolve(factory, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile();
            keyFirst = ModelQueryConfig.defaults().vendorProfiles(List.of(keyFirst(builtIn)));
        }

        /**
         * Checks each fixture on {@code target}; returns the names of those whose write was refused with
         * {@code MQ1601}, each a fixture whose read renders no predicate.
         */
        <E, V> List<String> check(Target<E, V> target, List<Fixture<V>> fixtures) {
            assertThat(fixtures).isNotEmpty();
            var refused = new ArrayList<String>();
            for (Fixture<V> fixture : fixtures) {
                if (refused(target, fixture)) {
                    refused.add(fixture.name());
                    continue;
                }
                for (WritePath path : WritePath.values()) {
                    assertUpdateParity(target, fixture, path);
                    if (target.delete() != null) {
                        assertDeleteParity(target, fixture, path);
                    }
                }
            }
            return refused;
        }

        /**
         * Whether the update refuses {@code fixture} with {@code MQ1601}; if it does, the delete does too and the read
         * renders no predicate (R-WRT-12).
         */
        private <E, V> boolean refused(Target<E, V> target, Fixture<V> fixture) {
            try {
                target.update().where(fixture.where()).build();
                return false;
            } catch (ModelQueryDefinitionException e) {
                assertThat(e.code()).as(fixture.name() + ": update").isEqualTo(MqCode.MQ1601);
            }
            if (target.delete() != null) {
                assertThatExceptionOfType(ModelQueryDefinitionException.class).as(fixture.name() + ": delete")
                        .isThrownBy(() -> target.delete().where(fixture.where()).build())
                        .satisfies(e -> assertThat(e.code()).isEqualTo(MqCode.MQ1601));
            }
            Object restriction = factory.fromSession(em -> target.read().where(fixture.where()).build()
                    .buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable()).query().getRestriction());
            assertThat(restriction).as(fixture.name() + ": read").isNull();
            return true;
        }

        private <E, V> void assertUpdateParity(Target<E, V> target, Fixture<V> fixture, WritePath path) {
            var builder = target.update().where(fixture.where());
            ModelUpdate<E, V> update = (path == WritePath.CHUNKED ? builder.chunked(ChunkOptions.size(CHUNK))
                    : builder).build();
            inRolledBackTransaction(em -> {
                target.setup().accept(em);
                Set<Long> expected = readIds(em, target, fixture);
                boolean readsTarget = readsTarget(update.entitiesReadInSubquery(em.getCriteriaBuilder(), portable()),
                        target.entity());
                statements.clear();
                long written = executor(em, target, path).update(update);
                List<String> sql = List.copyOf(statements);
                Set<Long> marked = new HashSet<>(em.createQuery(target.marked(), Long.class).getResultList());

                String as = fixture.name() + ", update, " + path;
                assertThat(marked).as(as).isEqualTo(expected);
                assertThat(written).as(as).isEqualTo(expected.size());
                assertPath(sql, readsTarget, path, as);
            });
        }

        /** Whether a sub-query reading {@code read} reads {@code root}'s table: no fixture maps two to one table. */
        private static boolean readsTarget(Set<Class<?>> read, Class<?> root) {
            return read.stream().anyMatch(type -> type.isAssignableFrom(root) || root.isAssignableFrom(type));
        }

        private <E, V> void assertDeleteParity(Target<E, V> target, Fixture<V> fixture, WritePath path) {
            var builder = target.delete().where(fixture.where());
            ModelDelete<E, V> delete = (path == WritePath.CHUNKED ? builder.chunked(ChunkOptions.size(CHUNK))
                    : builder).build();
            String allIds = "select e.id from " + target.entity().getSimpleName() + " e";
            inRolledBackTransaction(em -> {
                target.setup().accept(em);
                Set<Long> expected = readIds(em, target, fixture);
                Set<Long> deleted = new HashSet<>(em.createQuery(allIds, Long.class).getResultList());
                boolean readsTarget = readsTarget(delete.entitiesReadInSubquery(em.getCriteriaBuilder(), portable()),
                        target.entity());
                statements.clear();
                long written = executor(em, target, path).delete(delete);
                List<String> sql = List.copyOf(statements);
                deleted.removeAll(em.createQuery(allIds, Long.class).getResultList());

                String as = fixture.name() + ", delete, " + path;
                assertThat(deleted).as(as).isEqualTo(expected);
                assertThat(written).as(as).isEqualTo(expected.size());
                assertPath(sql, readsTarget, path, as);
            });
        }

        /**
         * The write took {@code path}: a key select runs exactly where the path selects keys first, which a chunked
         * write always does, and an unchunked one only where it reads its target in a sub-query that the profile
         * cannot render (R-WRT-11).
         */
        private void assertPath(List<String> sql, boolean readsTarget, WritePath path, String as) {
            boolean keySelects = switch (path) {
                case BUILT_IN -> readsTarget && !builtIn.targetTableInSubquery();
                case KEY_FIRST -> readsTarget;
                case CHUNKED -> true;
            };
            assertThat(sql.stream().anyMatch(statement -> statement.startsWith("select")))
                    .as(as + ": selects keys first in " + sql).isEqualTo(keySelects);
        }

        private <E> ModelQueryExecutor<E> executor(EntityManager em, Target<E, ?> target, WritePath path) {
            return ModelQueryExecutor.create(em, target.entity(),
                    path == WritePath.KEY_FIRST ? keyFirst : ModelQueryConfig.defaults());
        }

        private <E, V> Set<Long> readIds(EntityManager em, Target<E, V> target, Fixture<V> fixture) {
            BuiltQuery<V> built = target.read().where(fixture.where()).build()
                    .buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable());
            return em.createQuery(built.query()).getResultList().stream().map(built::map).map(target.id())
                    .collect(Collectors.toSet());
        }

        private void inRolledBackTransaction(Consumer<EntityManager> work) {
            factory.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.accept(em);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }

        @Override
        public void close() {
            factory.close();
        }
    }

    /** {@code builtIn}, except that it cannot read a write's target table in a sub-query (R-VND-11). */
    private static VendorProfile keyFirst(VendorProfile builtIn) {
        return new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return builtIn.vendor();
            }

            @Override
            public int maxInListSize() {
                return builtIn.maxInListSize();
            }

            @Override
            public int maxBindParameters() {
                return builtIn.maxBindParameters();
            }

            @Override
            public int streamingFetchSize(int requested) {
                return builtIn.streamingFetchSize(requested);
            }

            @Override
            public void checkStreamingPreconditions(EntityManager em) {
                builtIn.checkStreamingPreconditions(em);
            }

            @Override
            public void applyTimeout(Query query, Duration timeout) {
                builtIn.applyTimeout(query, timeout);
            }

            @Override
            public NullOrdering defaultAscendingNullOrdering() {
                return builtIn.defaultAscendingNullOrdering();
            }

            @Override
            public boolean targetTableInSubquery() {
                return false;
            }
        };
    }
}
