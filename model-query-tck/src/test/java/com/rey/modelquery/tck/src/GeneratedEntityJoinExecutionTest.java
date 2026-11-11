package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;

/** Processor-generated entity joins execute the same query as hand-written forms (AC-SRC-53, AC-PROC-48). */
class GeneratedEntityJoinExecutionTest {

    @TckTest
    void ac_src_53_ac_proc_48_generated_left_entity_joins_match_native_sql(TckDatabase db) throws SQLException {
        assertThat(QEntityJoinDetail.fields().resolve(List.of("viaOrder")).unknown()).isEmpty();
        assertThat(QEntityJoinDetail.MANAGER_VIP.identityPath()).isEqualTo("entity(manager).vip");
        assertThat(QEntityJoinDetail.CUSTOMER_VIP.identityPath()).isEqualTo("entity(customer).vip");
        var keys = QEntityJoinKeys.source()
                .where(f -> f.gte(QEntityJoinKeys.ID, 990L).lte(QEntityJoinKeys.ID, 1020L)).build();
        var query = QEntityJoinDetail.query(keys).select(QEntityJoinDetail.ALL)
                .where(f -> f.gte(QEntityJoinDetail.ID, 990L).lte(QEntityJoinDetail.ID, 1030L))
                .orderBy(QEntityJoinDetail.ID.asc()).build();
        List<EntityJoinDetail> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(query, Limit.unlimited())));
            }
        });
        assertThat(rows.stream().map(r ->
                SourceJoinExecutionTest.text(r.id(), r.managerName(), r.customerName(), r.viaOrder())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, m.name, c.name, t.name from orders o
                        left join (select id, customer_id from orders where id between 990 and 1020) k
                            on k.id = o.id
                        left join customers m on m.id = k.id
                        left join customers c on c.id = k.customer_id
                        left join orders cp on cp.id = k.id
                        left join customers t on t.id = cp.customer_id
                        where o.id between 990 and 1030 order by o.id"""));
        assertThat(rows).hasSize(41)
                .contains(new EntityJoinDetail(990L, "Customer 0990", "Customer 0631", "Customer 0631"))
                .anySatisfy(r -> {
                    assertThat(r.id()).isEqualTo(1001L);
                    assertThat(r.managerName()).isNull();
                    assertThat(r.customerName()).isNotNull();
                    assertThat(r.viaOrder()).isEqualTo(r.customerName());
                })
                .contains(new EntityJoinDetail(1021L, null, null, null));
    }

    @TckTest
    void ac_proc_49_ac_src_59_generated_column_anchored_joins_run_the_query(TckDatabase db) throws SQLException {
        var query = QColumnEntityJoinDetail.query().select(QColumnEntityJoinDetail.ALL)
                .where(f -> f.gte(QColumnEntityJoinDetail.ID, 990L).lte(QColumnEntityJoinDetail.ID, 1030L))
                .orderBy(QColumnEntityJoinDetail.ID.asc()).build();
        List<ColumnEntityJoinDetail> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(query, Limit.unlimited())));
            }
        });
        assertThat(rows.stream().map(r ->
                SourceJoinExecutionTest.text(r.id(), r.managerName(), r.viaOrder())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, m.name, t.name from orders o
                        join orders cp on cp.id = o.id
                        left join customers m on m.id = o.id and o.status = 'PAID'
                        left join customers t on t.id = cp.customer_id
                        where o.id between 990 and 1030 order by o.id"""));
        assertThat(rows).hasSize(41)
                .filteredOn(r -> r.managerName() != null).extracting(ColumnEntityJoinDetail::id)
                .containsExactly(993L, 997L);
        assertThat(sql).singleElement().satisfies(statement -> {
            assertThat(statement).doesNotContain("'PAID'");
            assertThat(statement.replace(" ", "")).containsPattern("status=\\?");
        });
    }

    @TckTest
    void ac_proc_50_generated_joined_entity_totals_match_native_sql(TckDatabase db) throws SQLException {
        var query = QEntityJoinTotals.query()
                .select(SelectSet.of(QEntityJoinTotals.COUNTRY, QEntityJoinTotals.REPS,
                        QEntityJoinTotals.ROWS, QEntityJoinTotals.LAST_HIRE))
                .where(f -> f.gte(QEntityJoinTotals.ID, 990L).lte(QEntityJoinTotals.ID, 1030L))
                .orderBy(QEntityJoinTotals.COUNTRY.asc()).build();
        List<EntityJoinTotals> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(query, Limit.unlimited())));
            }
        });
        List<EntityJoinTotals> nativeRows = new ArrayList<>();
        try (var connection = db.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        select c.country, count(c.id), count(c.id), max(c.created_at)
                        from orders o join customers c on c.id = o.referrer_id
                        where o.id between 990 and 1030 group by c.country order by c.country""")) {
            while (result.next()) {
                var lastHire = result.getTimestamp(4);
                nativeRows.add(new EntityJoinTotals(result.getString(1), result.getLong(2), result.getLong(3),
                        lastHire == null ? null : lastHire.toLocalDateTime()));
            }
        }
        assertThat(rows).isNotEmpty().containsExactlyElementsOf(nativeRows);
        assertThat(sql).anySatisfy(statement -> assertThat(statement.toLowerCase()).contains("count(*)"));
    }

    @TckTest
    void ac_gen_36_generated_entity_joins_run_nested_reads_and_presence_semantics(TckDatabase db)
            throws SQLException {
        var query = QOrderWithBuyerModel.query().select(QOrderWithBuyerModel.ALL
                        .with(QOrderWithBuyerModel.BUYER_SET)
                        .with(QOrderWithBuyerModel.SLOT_SET)
                        .with(QOrderWithBuyerModel.ORDER_COPY_SET)
                        .with(QOrderWithBuyerModel.ORDER_COPY_CUSTOMER))
                .where(f -> f.gte(QOrderWithBuyerModel.ID, 1L).lte(QOrderWithBuyerModel.ID, 35L))
                .orderBy(QOrderWithBuyerModel.ID.asc()).build();
        List<OrderWithBuyerModel> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(query, Limit.unlimited())));
            }
        });
        // 1. LEFT miss -> Optional.empty(), match -> present
        assertThat(rows).filteredOn(r -> r.id() == 1L || r.id() == 2L)
                .allSatisfy(r -> assertThat(r.buyer()).isEmpty());
        assertThat(rows).filteredOn(r -> r.id() == 3L)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.buyer()).isPresent();
                    assertThat(r.buyer().get().name()).isEqualTo("Customer 0034");
                });

        // 2. match with all-NULL non-key columns -> present
        assertThat(rows).filteredOn(r -> r.id() == 35L)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.slot()).isPresent();
                    assertThat(r.slot().get().id()).isEqualTo(35L);
                    assertThat(r.slot().get().sortInt()).isNull();
                    assertThat(r.slot().get().sortText()).isNull();
                });

        // 5. a @Join below the nested model (to-one): orderCopy has customer
        assertThat(rows).filteredOn(r -> r.id() == 1L)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.orderCopy()).isPresent();
                    assertThat(r.orderCopy().get().customer()).isPresent();
                    assertThat(r.orderCopy().get().customer().get().name()).isNotNull();
                });

        // 3. INNER drops misses
        var innerQuery = QInnerOrderWithBuyerModel.query()
                .select(QInnerOrderWithBuyerModel.ALL.with(QInnerOrderWithBuyerModel.BUYER_SET))
                .where(f -> f.gte(QInnerOrderWithBuyerModel.ID, 1L).lte(QInnerOrderWithBuyerModel.ID, 30L))
                .orderBy(QInnerOrderWithBuyerModel.ID.asc()).build();
        List<InnerOrderWithBuyerModel> innerRows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> innerRows.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(innerQuery, Limit.unlimited())));
            }
        });
        assertThat(innerRows).hasSize(10).allSatisfy(r -> assertThat(r.buyer()).isPresent());

        // 4. buyer.name sort and filter path
        var filteredQuery = QOrderWithBuyerModel.query()
                .select(QOrderWithBuyerModel.ALL.with(QOrderWithBuyerModel.BUYER_SET))
                .where(f -> f.eq(QOrderWithBuyerModel.BUYER_NAME, "Customer 0034")).build();
        List<OrderWithBuyerModel> filtered = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> filtered.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(filteredQuery, Limit.unlimited())));
            }
        });
        assertThat(filtered).extracting(OrderWithBuyerModel::id).contains(3L);

        var sortedQuery = QInnerOrderWithBuyerModel.query()
                .select(QInnerOrderWithBuyerModel.ALL.with(QInnerOrderWithBuyerModel.BUYER_SET))
                .where(f -> f.gte(QInnerOrderWithBuyerModel.ID, 1L).lte(QInnerOrderWithBuyerModel.ID, 30L))
                .orderBy(QInnerOrderWithBuyerModel.BUYER_NAME.desc()).build();
        List<InnerOrderWithBuyerModel> sorted = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> sorted.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(sortedQuery, Limit.unlimited())));
            }
        });
        assertThat(sorted).hasSize(10).extracting(r -> r.buyer().orElseThrow().name())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @TckTest
    void ac_gen_47_generated_entity_joins_anchored_on_earlier_joins_match_native_sql(TckDatabase db)
            throws SQLException {
        var query = QChainedEntityJoinDetail.query().select(QChainedEntityJoinDetail.ALL)
                .where(f -> f.gte(QChainedEntityJoinDetail.ID, 990L).lte(QChainedEntityJoinDetail.ID, 1030L))
                .orderBy(QChainedEntityJoinDetail.ID.asc()).build();
        List<ChainedEntityJoinDetail> rows = new ArrayList<>();
        List<Long> count = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                    rows.addAll(executor.list(query, Limit.unlimited()));
                    count.add(executor.count(query));
                });
            }
        });
        assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.referrerId(), r.referredId(),
                r.referredName(), r.twinReferrerId(), r.twinReferredName(), r.twinCustomerName())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, o.referrer_id, r.id, r.name, t.referrer_id, tr.name, tc.name from orders o
                        left join customers r on r.id = o.referrer_id
                        left join orders t on t.id = r.id
                        left join customers tr on tr.id = t.referrer_id
                        left join customers tc on tc.id = t.customer_id
                        where o.id between 990 and 1030 order by o.id"""));
        // Each link reads a row on some orders and misses on others, so the chain is exercised end to end.
        assertThat(rows).hasSize(41)
                .anySatisfy(r -> assertThat(r.twinReferredName()).isNotNull())
                .anySatisfy(r -> assertThat(r.twinCustomerName()).isNotNull())
                .anySatisfy(r -> {
                    assertThat(r.referredName()).isNotNull();
                    assertThat(r.twinReferredName()).isNull();
                })
                .anySatisfy(r -> assertThat(r.referredName()).isNull());
        assertThat(count).containsExactly(41L);
        // The joins render in declaration order: each ON reads only a table joined before it.
        assertThat(sql).hasSize(2).allSatisfy(statement -> assertThat(statement)
                .containsPattern("join customers \\w+ on .* join orders \\w+ on .* join customers \\w+ on .* "
                        + "join customers \\w+ on "));
    }

    @TckTest
    void ac_gen_47_a_nested_entity_join_below_an_entity_join_matches_native_sql(TckDatabase db)
            throws SQLException {
        var query = QOrderWithLabelledReferrer.query()
                .select(QOrderWithLabelledReferrer.ALL.with(QOrderWithLabelledReferrer.REFERRED_SET))
                .where(f -> f.in(QOrderWithLabelledReferrer.ID, List.of(1L, 2L, 3L, 273L, 546L, 819L, 946L, 973L)))
                .orderBy(QOrderWithLabelledReferrer.ID.asc()).build();
        List<OrderWithLabelledReferrer> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(query, Limit.unlimited())));
            }
        });
        assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.referrerId(),
                r.referred().map(LabelledCustomer::id).orElse(null),
                r.referred().map(LabelledCustomer::name).orElse(null),
                r.referred().map(LabelledCustomer::labelName).orElse(null))))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, o.referrer_id, r.id, r.name, rl.name from orders o
                        left join customers r on r.id = o.referrer_id
                        left join labels rl on rl.id = r.id
                        where o.id in (1, 2, 3, 273, 546, 819, 946, 973) order by o.id"""));
        // A labelled referrer, an unlabelled one, and an order without a referrer.
        assertThat(rows)
                .anySatisfy(r -> assertThat(r.referred())
                        .hasValueSatisfying(c -> assertThat(c.labelName()).isNotNull()))
                .anySatisfy(r -> assertThat(r.referred()).hasValueSatisfying(c -> assertThat(c.labelName()).isNull()))
                .anySatisfy(r -> assertThat(r.referred()).isEmpty());
    }
}
