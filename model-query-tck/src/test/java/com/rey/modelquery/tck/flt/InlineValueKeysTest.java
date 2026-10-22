package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.KeysetTypeEntity;
import com.rey.modelquery.tck.col.LabelEntity;
import com.rey.modelquery.tck.fch.CustomerOrders;
import com.rey.modelquery.tck.fch.FetchTestSupport;
import com.rey.modelquery.tck.fch.LabelOrders;
import com.rey.modelquery.tck.fch.OrderLines;
import com.rey.modelquery.tck.fch.QCustomerOrders;
import com.rey.modelquery.tck.fch.QLabelOrders;
import com.rey.modelquery.tck.fch.QLine;
import com.rey.modelquery.tck.fch.QOrderLines;
import com.rey.modelquery.tck.harness.InlineValueHandling;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.SessionFactory;

/**
 * Key and fetch-plan key values under {@code hibernate.criteria.value_handling_mode=inline}: {@code one(q, key)},
 * {@code byKeys} chunks, primary-key-first pages and fetch-plan child rounds bind every key as a parameter, typed by
 * its column's mapping, so no input reaches the SQL text, and return what they return under the default setting; a
 * provider with no operand binder binds a decimal key and refuses a {@code UUID} or a converted key, a
 * primary-key-first page's {@code UUID} keys and a {@code UUID} fetch key with {@code MQ1502} before any statement
 * (spec api/12 R-FLT-08, R-FLT-21, vendor/40 R-VND-28, D-177; TCK AC-FLT-35).
 */
class InlineValueKeysTest {

    record T(Long id) {}

    private static final TableField<KeysetTypeEntity, KeysetTypeEntity> TYPES =
            TableField.root(KeysetTypeEntity.class);
    private static final ColumnField<T, KeysetTypeEntity, Long> ID = ColumnField.of(T.class, TYPES, "id", Long.class);
    private static final ColumnField<T, KeysetTypeEntity, BigDecimal> AMOUNT =
            ColumnField.of(T.class, TYPES, "amount", BigDecimal.class);
    private static final ColumnField<T, KeysetTypeEntity, Timestamp> STAMP =
            ColumnField.of(T.class, TYPES, "stamp", Timestamp.class);
    /** A {@code UUID} stored as {@code varchar(36)}, so its value binds only typed by the mapping. */
    private static final ColumnField<T, KeysetTypeEntity, UUID> TOKEN =
            ColumnField.of(T.class, TYPES, "token", UUID.class);
    /** A value class through a JPA {@code AttributeConverter}. */
    private static final ColumnField<T, KeysetTypeEntity, KeysetTypeEntity.Shape> SHAPE =
            ColumnField.of(T.class, TYPES, "shape", KeysetTypeEntity.Shape.class);
    private static final ModelQuery.Builder<KeysetTypeEntity, Object, T> TYPE_ROWS =
            ModelQuery.builder(TYPES, row -> new T(row.get(ID))).select(SelectSet.of(ID, AMOUNT, STAMP, TOKEN, SHAPE))
                    .orderBy(ID.asc());
    /** Rows of {@code keyset_types} odd enough that no other number in a statement is one of their values. */
    private static final List<Integer> ROWS = List.of(3, 7, 11, 17, 23, 29, 31);
    private static final LocalDateTime BASE = LocalDateTime.of(2020, 1, 1, 0, 0);

    /** One key column: its query and how a fixture row's key value and its watched SQL text are made. */
    private record KeyCase<K>(String path, PrimaryKey<T, K> primaryKey, Function<Integer, K> key,
            Function<Integer, Object> watched) {}

    private static final List<KeyCase<?>> KEY_CASES = List.of(
            new KeyCase<>("UUID key", PrimaryKey.of(TOKEN), InlineValueKeysTest::token,
                    i -> token(i).toString()),
            new KeyCase<>("decimal key", PrimaryKey.of(AMOUNT), InlineValueKeysTest::amount,
                    InlineValueKeysTest::amount),
            new KeyCase<>("timestamp key", PrimaryKey.of(STAMP), InlineValueKeysTest::stamp,
                    i -> BASE.plusSeconds(i).toLocalTime().toString()),
            new KeyCase<>("composite key", PrimaryKey.composite(TOKEN, AMOUNT),
                    i -> List.<Object>of(token(i), amount(i)), i -> token(i).toString()));

    private static final FetchPlan<CustomerOrders> CUSTOMER = FetchPlan.of(QCustomerOrders.ALL)
            .child(QCustomerOrders.ORDERS, FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS,
                    FetchPlan.of(QLine.ALL)));
    private static final FetchPlan<LabelOrders> LABEL =
            FetchPlan.of(QLabelOrders.ALL).child(QLabelOrders.ORDERS, FetchPlan.of(QOrderLines.ALL));

    // ---- AC-FLT-35: keys

    @TckTest
    void ac_flt_35_each_key_path_binds_its_keys_and_returns_the_default_settings_rows(TckDatabase db) {
        // Three keys per statement, so seven keys read in three byKeys chunks (R-EXE-13).
        ModelQueryConfig chunked = ModelQueryConfig.defaults().vendorProfiles(List.of(FetchTestSupport.limited(db, 3)));
        for (KeyCase<?> c : KEY_CASES) {
            List<Object> defaults;
            try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
                defaults = keyReads(sf, chunked, c);
            }
            assertThat(defaults).as(c.path()).containsExactly(List.of(new T(17L)), ids(ROWS), ids(ROWS.subList(2, 5)));
            try (InlineValueHandling inline = InlineValueHandling.open(db)) {
                inline.watch(ROWS.stream().map(c.watched()).toList(), List.of());
                assertThat(keyReads(inline.factory(), chunked, c)).as(c.path()).isEqualTo(defaults);
                assertBound(inline, c.path());
            }
        }
    }

    @TckTest
    void ac_flt_35_a_converted_key_binds_typed_by_its_mapping(TckDatabase db) {
        var q = TYPE_ROWS.where(f -> f.eq(ID, 6L)).primaryKey(PrimaryKey.of(SHAPE)).build();
        var shape = new KeysetTypeEntity.Shape("shape-1");
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            inline.watch("shape-1");
            inline.factory().inSession(em -> assertThat(executor(em).one(q, shape)).contains(new T(6L)));
            assertBound(inline, "converted key");
        }
    }

    @TckTest
    void ac_flt_35_without_a_binder_a_decimal_key_binds_and_a_uuid_or_converted_key_is_mq1502_before_any_statement(
            TckDatabase db) {
        var byAmount = keyed(PrimaryKey.of(AMOUNT));
        var byToken = keyed(PrimaryKey.of(TOKEN));
        var byShape = TYPE_ROWS.primaryKey(PrimaryKey.of(SHAPE)).build();
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            OperatorProviderSupport.serving(feature -> false, null, () -> inline.factory().inSession(em -> {
                var executor = executor(em);
                inline.watch(amount(7));
                assertThat(executor.byKeys(byAmount, List.of(amount(7))).values()).containsExactly(new T(7L));
                assertBound(inline, "decimal key without a binder");
                inline.watch(token(7).toString());
                assertThatThrownBy(() -> executor.one(byToken, token(7)))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                        .hasMessageContaining("token").hasMessageContaining("the key under inline value handling")
                        .hasMessageContaining("UUID");
                assertThatThrownBy(() -> executor.byKeys(byToken, List.of(token(7), token(11))))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502));
                assertThatThrownBy(() -> executor.one(byShape, new KeysetTypeEntity.Shape("shape-1")))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                        .hasMessageContaining("shape").hasMessageContaining("Shape");
                assertThat(inline.statements()).isEmpty();
            }));
        }
    }

    @TckTest
    void ac_flt_35_without_a_binder_primary_key_first_uuid_keys_are_mq1502_before_step_1_even_on_an_empty_page(
            TckDatabase db) {
        // Step 2 binds the keys step 1 read: refused before step 1, so an empty page is refused alike (R-FLT-08).
        var some = keyed(PrimaryKey.of(TOKEN));
        var none = TYPE_ROWS.where(f -> f.eq(ID, -1L)).primaryKey(PrimaryKey.of(TOKEN))
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            OperatorProviderSupport.serving(feature -> false, null, () -> inline.factory().inSession(em -> {
                var executor = executor(em);
                inline.watch(token(7).toString());
                for (var q : List.of(some, none)) {
                    assertThatThrownBy(() -> executor.page(q, PageSpec.ofOffset(1, 3), CountMode.COUNT))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                            .hasMessageContaining("token").hasMessageContaining("the key under inline value handling")
                            .hasMessageContaining("UUID");
                    assertThatThrownBy(() -> executor.export(q, ExportOptions.of(3), page -> page, row -> { }))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502));
                }
                assertThat(inline.statements()).isEmpty();
            }));
        }
    }

    // ---- AC-FLT-35: fetch-plan keys

    @TckTest
    void ac_flt_35_fetch_plan_round_keys_bind_and_load_the_default_settings_children(TckDatabase db) {
        // Customers 33 to 39 and labels 7 to 9: their ids are the round keys of the first child level.
        ModelQuery<CustomerEntity, Long, CustomerOrders> customers = QCustomerOrders.query()
                .where(f -> f.between(QCustomerOrders.ID, 33L, 39L)).orderBy(QCustomerOrders.ID.asc())
                .fetch(CUSTOMER).build();
        ModelQuery<LabelEntity, Long, LabelOrders> labels = QLabelOrders.query()
                .where(f -> f.between(QLabelOrders.ID, 7L, 9L)).orderBy(QLabelOrders.ID.asc()).fetch(LABEL).build();
        List<CustomerOrders> customerDefaults;
        List<LabelOrders> labelDefaults;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            customerDefaults = listed(sf, CustomerEntity.class, customers);
            labelDefaults = listed(sf, LabelEntity.class, labels);
        }
        // Each level loads children under the default setting, so matching them is no accident of empty rounds.
        assertThat(customerDefaults).flatMap(CustomerOrders::orders).flatMap(OrderLines::items).isNotEmpty();
        assertThat(labelDefaults).flatMap(LabelOrders::orders).isNotEmpty();
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            inline.watch(34L, 35L, 36L, 37L, 38L);
            assertThat(listed(inline.factory(), CustomerEntity.class, customers)).isEqualTo(customerDefaults);
            assertBound(inline, "fetch key");
            assertThat(inline.statements()).hasSize(3);
            inline.watch(8L);
            assertThat(listed(inline.factory(), LabelEntity.class, labels)).isEqualTo(labelDefaults);
            assertBound(inline, "through fetch key");
            assertThat(inline.statements()).hasSize(2);
        }
    }

    @TckTest
    void ac_flt_35_without_a_binder_a_uuid_fetch_key_is_mq1502_before_the_parent_statement_even_with_no_parents(
            TckDatabase db) {
        // The round keys bind after the parent statement ran: refused before it, so no parents are refused alike.
        FetchPlan<TokenTwins> plan =
                FetchPlan.of(QTokenTwins.ALL).child(QTokenTwins.TWINS, FetchPlan.of(QTokenTwin.ALL));
        var some = QTokenTwins.query().where(f -> f.in(QTokenTwins.ID, List.of(7L, 11L)))
                .orderBy(QTokenTwins.ID.asc()).fetch(plan).build();
        var none = QTokenTwins.query().where(f -> f.eq(QTokenTwins.ID, -1L)).orderBy(QTokenTwins.ID.asc())
                .fetch(plan).build();
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            OperatorProviderSupport.serving(feature -> false, null, () -> inline.factory().inSession(em -> {
                var executor = executor(em);
                inline.watch(token(7).toString());
                for (var q : List.of(some, none)) {
                    assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                            .hasMessageContaining("token")
                            .hasMessageContaining("the fetch-plan key of TokenTwins.twins")
                            .hasMessageContaining("UUID");
                    assertThatThrownBy(() -> executor.export(q, ExportOptions.of(3), page -> page, row -> { }))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502));
                }
                assertThat(inline.statements()).isEmpty();
            }));
        }
    }

    // ---- helpers

    private static <K> ModelQuery<KeysetTypeEntity, K, T> keyed(PrimaryKey<T, K> key) {
        return TYPE_ROWS.primaryKey(key).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
    }

    /** {@code one(q, key)} of row 17, {@code byKeys} of {@link #ROWS} and a primary-key-first page of rows 11–23. */
    private static <K> List<Object> keyReads(SessionFactory sf, ModelQueryConfig config, KeyCase<K> c) {
        List<Object> reads = new ArrayList<>();
        sf.inSession(em -> {
            var executor = ModelQueryExecutor.create(em, KeysetTypeEntity.class, config);
            var q = keyed(c.primaryKey());
            reads.add(executor.one(q, c.key().apply(17)).stream().toList());
            Map<K, T> byKeys = executor.byKeys(q, ROWS.stream().map(c.key()).toList());
            reads.add(List.copyOf(byKeys.values()));
            // Rows 11, 17 and 23 at offset 1 of rows 7 to 23: the page reads their keys, then their rows by key.
            var page = TYPE_ROWS.where(f -> f.in(ID, List.of(7L, 11L, 17L, 23L))).primaryKey(c.primaryKey())
                    .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
            reads.add(executor.page(page, PageSpec.ofOffset(1, 3), CountMode.NO_COUNT).content());
        });
        return reads;
    }

    private static <E, M> List<M> listed(SessionFactory sf, Class<E> root, ModelQuery<E, ?, M> q) {
        List<M> models = new ArrayList<>();
        sf.inSession(em -> models.addAll(
                ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults()).list(q, Limit.unlimited())));
        return models;
    }

    private static ModelQueryExecutor<KeysetTypeEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, KeysetTypeEntity.class, ModelQueryConfig.defaults());
    }

    private static List<T> ids(List<Integer> rows) {
        return rows.stream().map(i -> new T((long) i)).toList();
    }

    private static UUID token(int i) {
        return new UUID(0, i);
    }

    private static BigDecimal amount(int i) {
        return BigDecimal.valueOf((i * 37L) % 10_000, 4);
    }

    private static Timestamp stamp(int i) {
        Timestamp stamp = Timestamp.valueOf(BASE.plusSeconds(i));
        stamp.setNanos((i % 997) * 1_000);
        return stamp;
    }

    /** Each statement since the last {@code watch} ran: no input reached it (the inspector checked). */
    private static void assertBound(InlineValueHandling inline, String path) {
        assertThat(inline.statements()).as(path).isNotEmpty();
    }
}
