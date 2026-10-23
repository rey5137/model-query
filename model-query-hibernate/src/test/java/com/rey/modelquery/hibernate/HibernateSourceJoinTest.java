package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hibernate's {@code SourceRenderer#join} on H2, on whichever line the build runs: a grouped source joined to an
 * entity-rooted query on its key, in every statement the executor runs (R-SRC-21, R-SRC-22; the TCK covers every
 * vendor).
 */
class HibernateSourceJoinTest {

    @Entity(name = "JsDay")
    static class Day {
        @Id Long id;
        LocalDate businessDate;
    }

    @Entity(name = "JsSale")
    static class Sale {
        @Id Long id;
        LocalDate businessDate;
        String shop;
        BigDecimal cash;
    }

    static final class DaySales {}

    record View(Long id, BigDecimal north, BigDecimal south) {}

    record Counted(Long id, Long sales) {}

    /** A source of one row per day and shop, keyed by both: the day's sales count ties across rows. */
    static final class ShopDays {}

    record Pick(LocalDate day) {}

    /** A chain 1-2-3-4, a lone node 5 and a cycle 10-11: the walk's entity and the joining query's root. */
    @Entity(name = "JsNode")
    static class Node {
        @Id Long id;

        @Column(name = "parentId")
        Long parentId;

        @ManyToOne
        @JoinColumn(name = "parentId", insertable = false, updatable = false,
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        Node parent;

        @OneToMany(mappedBy = "parent")
        List<Node> children;
    }

    /** The walk's model: a node, its parent and its level. */
    static final class Walked {}

    /** A grouped source over the walk: its nodes per parent. */
    static final class Kids {}

    record Leveled(Long id, Integer level) {}

    record Counts(Long id, Long children) {}

    record Parent(Long id, List<Leveled> kids) {}

    /** A {@code JpaFrom} stand-in whose CTE join returns a type with no {@code on(Predicate...)}. */
    public static final class JoinWithoutOn {
        public Object join(JpaCteCriteria<?> cte, SqmJoinType type) {
            return null;
        }
    }

    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
    private static final TableField<Sale, Sale> SALE = TableField.root(Sale.class);
    private static final ColumnField<DaySales, Sale, LocalDate> DS_DATE =
            ColumnField.of(DaySales.class, SALE, "businessDate", LocalDate.class).named("businessDate");
    private static final ColumnField<DaySales, Sale, String> DS_SHOP =
            ColumnField.of(DaySales.class, SALE, "shop", String.class);
    private static final AggregateField<DaySales, BigDecimal> DS_CASH =
            Agg.sum(ColumnField.of(DaySales.class, SALE, "cash", BigDecimal.class)).named("cash");

    private static final TableField<Day, Day> DAY = TableField.root(Day.class);
    private static final OrderedColumnField<View, Day, Long> ID =
            ColumnField.of(View.class, DAY, "id", Long.class).named("id");
    private static final ColumnField<View, Day, LocalDate> DATE =
            ColumnField.of(View.class, DAY, "businessDate", LocalDate.class).named("businessDate");
    private static final SourceJoin<View, DaySales> NORTH = join("north", JoinKind.LEFT);
    private static final SourceJoin<View, DaySales> SOUTH = join("south", JoinKind.LEFT);
    private static final SourceJoin<View, DaySales> NORTH_ONLY = join("north", JoinKind.INNER);
    private static final OrderedColumnField<View, DaySales, BigDecimal> NORTH_CASH =
            ColumnField.over(View.class, NORTH.root(), DS_CASH).named("north");
    private static final OrderedColumnField<View, DaySales, BigDecimal> SOUTH_CASH =
            ColumnField.over(View.class, SOUTH.root(), DS_CASH).named("south");

    private static final OrderedColumnField<Counted, Day, Long> COUNTED_ID =
            ColumnField.of(Counted.class, DAY, "id", Long.class).named("id");
    private static final ColumnField<Counted, Day, LocalDate> COUNTED_DATE =
            ColumnField.of(Counted.class, DAY, "businessDate", LocalDate.class);
    private static final SourceJoin<Counted, DaySales> COUNTED_SOUTH = SourceJoin.of("south", DaySales.class,
            JoinKind.LEFT, List.of(SourceJoin.on(COUNTED_DATE, DS_DATE)));
    private static final SourceJoin<Counted, DaySales> COUNTED_NORTH = SourceJoin.of("north", DaySales.class,
            JoinKind.INNER, List.of(SourceJoin.on(COUNTED_DATE, DS_DATE)));

    private static final ColumnField<ShopDays, Sale, LocalDate> SD_DATE =
            ColumnField.of(ShopDays.class, SALE, "businessDate", LocalDate.class).named("businessDate");
    private static final ColumnField<ShopDays, Sale, String> SD_SHOP =
            ColumnField.of(ShopDays.class, SALE, "shop", String.class).named("shop");
    private static final AggregateField<ShopDays, Long> SD_SALES = Agg.<ShopDays>count(SALE).named("sales");
    private static final SourceQuery<ShopDays> SHOP_DAYS = SourceQuery.builder(ShopDays.class, SALE,
            SelectSet.of(SD_DATE, SD_SHOP, SD_SALES), PrimaryKey.composite(SD_DATE, SD_SHOP),
            SelectSet.of(SD_DATE, SD_SHOP)).build();
    private static final TableField<ShopDays, ShopDays> SHOP_DAY = TableField.source(ShopDays.class);

    private static final TableField<Node, Node> NODE = TableField.root(Node.class);
    private static final ColumnField<Walked, Node, Long> W_ID =
            ColumnField.of(Walked.class, NODE, "id", Long.class).named("id");
    private static final ColumnField<Walked, Node, Long> W_PARENT =
            ColumnField.of(Walked.class, NODE, "parentId", Long.class).named("parentId");
    private static final ColumnField<Walked, Node, Integer> W_LEVEL =
            ColumnField.of(Walked.class, NODE, "level", Integer.class).named("level");
    private static final TableField<Walked, Walked> WALKED = TableField.source(Walked.class);
    private static final ColumnField<Kids, Walked, Long> K_PARENT =
            ColumnField.over(Kids.class, WALKED, W_PARENT).named("parentId");
    private static final AggregateField<Kids, Long> K_CHILDREN = Agg.<Kids>count(WALKED).named("children");

    private static final OrderedColumnField<Leveled, Node, Long> L_ID =
            ColumnField.of(Leveled.class, NODE, "id", Long.class).named("id");
    private static final SourceJoin<Leveled, Walked> NODES = SourceJoin.of("nodes", Walked.class, JoinKind.LEFT,
            List.of(SourceJoin.on(L_ID, W_ID)));
    private static final SourceJoin<Leveled, Walked> NODES_ONLY = SourceJoin.of("nodes", Walked.class,
            JoinKind.INNER, List.of(SourceJoin.on(L_ID, W_ID)));
    private static final ColumnField<Leveled, Walked, Integer> L_LEVEL =
            ColumnField.over(Leveled.class, NODES.root(), W_LEVEL).named("level");
    private static final OrderedColumnField<Counts, Node, Long> C_ID =
            ColumnField.of(Counts.class, NODE, "id", Long.class).named("id");
    private static final SourceJoin<Counts, Kids> KIDS = SourceJoin.of("kids", Kids.class, JoinKind.LEFT,
            List.of(SourceJoin.on(C_ID, K_PARENT)));
    private static final ColumnField<Counts, Kids, Long> C_CHILDREN =
            ColumnField.over(Counts.class, KIDS.root(), K_CHILDREN).named("children");
    private static final OrderedColumnField<Parent, Node, Long> P_ID =
            ColumnField.of(Parent.class, NODE, "id", Long.class).named("id");

    private static final List<String> SQL = new ArrayList<>();
    private static SessionFactory factory;

    private static SourceJoin<View, DaySales> join(String name, JoinKind kind) {
        return SourceJoin.of(name, DaySales.class, kind, List.of(SourceJoin.on(DATE, DS_DATE)));
    }

    /** The day sums of {@code shop}: its {@code where} value is the body's parameter, bound by each statement. */
    private static SourceQuery<DaySales> sales(String shop) {
        return SourceQuery.builder(DaySales.class, SALE, SelectSet.of(DS_DATE, DS_CASH), PrimaryKey.of(DS_DATE),
                SelectSet.of(DS_DATE)).where(f -> f.eq(DS_SHOP, shop)).build();
    }

    /** The walk from {@code seeds} to their children, a checked tree or a plain {@code recursive(...)}. */
    private static SourceQuery<Walked> walk(boolean tree, int maxDepth, Long... seeds) {
        var builder = SourceQuery.builder(Walked.class, NODE, SelectSet.of(W_ID, W_PARENT, W_LEVEL),
                PrimaryKey.of(W_ID)).depth(W_LEVEL).where(f -> f.in(W_ID, List.of(seeds)));
        return (tree ? builder.tree(step -> step.childKey(W_PARENT).parentKey(W_ID), maxDepth)
                : builder.recursive(step -> step.childKey(W_PARENT).parentKey(W_ID), maxDepth)).build();
    }

    /** Every node with its level in {@code walk}, joined {@code join}. */
    private static ModelQuery.Builder<Node, Long, Leveled> leveled(SourceJoin<Leveled, Walked> join,
            SourceQuery<Walked> walk) {
        return ModelQuery.builder(NODE, row -> new Leveled(row.get(L_ID), row.get(L_LEVEL)))
                .joinSource(join, walk).select(SelectSet.of(L_ID, L_LEVEL)).primaryKey(PrimaryKey.of(L_ID))
                .orderBy(L_ID.asc());
    }

    /** Every node with its children in {@code walk}, counted by a grouped source over it. */
    private static ModelQuery<Node, Long, Counts> counted(SourceQuery<Walked> walk) {
        var kids = SourceQuery.builder(Kids.class, WALKED, SelectSet.of(K_PARENT, K_CHILDREN),
                PrimaryKey.of(K_PARENT), SelectSet.of(K_PARENT), walk).build();
        return ModelQuery.builder(NODE, row -> new Counts(row.get(C_ID), row.get(C_CHILDREN)))
                .joinSource(KIDS, kids).select(SelectSet.of(C_ID, C_CHILDREN)).primaryKey(PrimaryKey.of(C_ID))
                .orderBy(C_ID.asc()).build();
    }

    private static ModelQuery.Builder<Day, Long, View> both() {
        return ModelQuery.builder(DAY, row -> new View(row.get(ID), row.get(NORTH_CASH), row.get(SOUTH_CASH)))
                .joinSource(NORTH, sales("north")).joinSource(SOUTH, sales("south"))
                .select(SelectSet.of(ID, NORTH_CASH, SOUTH_CASH)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc());
    }

    @BeforeAll
    static void start() {
        factory = new Configuration().addAnnotatedClass(Day.class).addAnnotatedClass(Sale.class)
                .addAnnotatedClass(Node.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:source-join")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .setStatementInspector((StatementInspector) sql -> {
                    SQL.add(sql);
                    return sql;
                }).buildSessionFactory();
        factory.inTransaction(em -> {
            for (long id = 1; id <= 3; id++) {
                var day = new Day();
                day.id = id;
                day.businessDate = FIRST.plusDays(id - 1);
                em.persist(day);
            }
            long id = 0;
            for (Object[] sale : new Object[][] {{0, "north", "10"}, {0, "north", "5"}, {0, "south", "7"},
                    {1, "south", "4"}}) {
                var row = new Sale();
                row.id = ++id;
                row.businessDate = FIRST.plusDays((Integer) sale[0]);
                row.shop = (String) sale[1];
                row.cash = new BigDecimal((String) sale[2]);
                em.persist(row);
            }
            for (Long[] link : new Long[][] {{1L, null}, {2L, 1L}, {3L, 2L}, {4L, 3L}, {5L, null}, {10L, 11L},
                    {11L, 10L}}) {
                var node = new Node();
                node.id = link[0];
                node.parentId = link[1];
                em.persist(node);
            }
        });
    }

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void ac_src_42_two_left_joins_of_one_source_model_stay_apart_and_unmatched_rows_read_null() {
        executing(executor -> {
            List<View> rows = executor.list(both().build(), Limit.unlimited());

            assertThat(rows).extracting(View::id).containsExactly(1L, 2L, 3L);
            assertThat(rows.get(0).north()).isEqualByComparingTo("15");
            assertThat(rows.get(0).south()).isEqualByComparingTo("7");
            assertThat(rows.get(1).north()).isNull();
            assertThat(rows.get(1).south()).isEqualByComparingTo("4");
            assertThat(rows.get(2).north()).isNull();
            assertThat(rows.get(2).south()).isNull();
            assertThat(SQL).singleElement().satisfies(sql -> {
                assertThat(occurrences(sql, "left join")).isEqualTo(2);
                // Each body's shop value, bound once by the statement.
                assertThat(occurrences(sql, "?")).isEqualTo(2);
            });
        });
    }

    @Test
    void ac_src_43_every_statement_the_executor_runs_carries_both_joins() {
        var paged = both().primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0L)).build();
        executing(executor -> {
            assertThat(executor.page(both().build(), PageSpec.of(0, 2), CountMode.COUNT).total()).hasValue(3L);
            assertThat(executor.page(paged, PageSpec.ofOffset(1, 1), CountMode.NO_COUNT).content())
                    .extracting(View::id).containsExactly(2L);
            assertThat(executor.byKeys(both().build(), List.of(3L, 1L))).containsOnlyKeys(1L, 3L);
            var exported = new ArrayList<Long>();
            executor.export(both().build(), ExportOptions.of(2), page -> page, view -> exported.add(view.id()));
            assertThat(exported).containsExactly(1L, 2L, 3L);
            // content and count; the key step and its model step; byKeys; two export pages, the second empty or not
            assertThat(SQL).hasSizeGreaterThanOrEqualTo(6)
                    .allSatisfy(sql -> assertThat(occurrences(sql, "left join")).as(sql).isEqualTo(2));
        });
    }

    @Test
    void ac_src_44_inner_drops_unmatched_rows_in_content_and_count_and_a_filter_reads_the_joined_column() {
        // NORTH_CASH reads the INNER join too: its table is keyed by the join's name and source model.
        var inner = ModelQuery.builder(DAY, row -> new View(row.get(ID), row.get(NORTH_CASH), null))
                .joinSource(NORTH_ONLY, sales("north")).select(SelectSet.of(ID, NORTH_CASH)).orderBy(ID.asc())
                .build();
        var unmatched = both().where(f -> f.isNull(NORTH_CASH)).build();
        executing(executor -> {
            assertThat(executor.list(inner, Limit.unlimited())).extracting(View::id).containsExactly(1L);
            assertThat(executor.count(inner)).isEqualTo(1L);
            assertThat(executor.list(unmatched, Limit.unlimited())).extracting(View::id).containsExactly(2L, 3L);
            assertThat(executor.count(unmatched)).isEqualTo(2L);
        });
    }

    @Test
    void ac_agg_29_a_count_of_a_left_joined_source_counts_its_first_key_so_an_unmatched_row_counts_0() {
        AggregateField<Counted, Long> sales = Agg.count(COUNTED_SOUTH.root());
        // A filter every row passes: the count under it still counts the key, not a constant.
        AggregateField<Counted, Long> filtered = Agg.<Counted>count(COUNTED_SOUTH.root())
                .filter(f -> f.gt(COUNTED_ID, 0L));
        executing(executor -> {
            for (AggregateField<Counted, Long> count : List.of(sales, filtered)) {
                var grouped = ModelQuery.builder(DAY, row -> new Counted(row.get(COUNTED_ID), row.get(count)))
                        .joinSource(COUNTED_SOUTH, sales("south")).select(SelectSet.of(COUNTED_ID, count))
                        .groupBy(SelectSet.of(COUNTED_ID)).orderBy(COUNTED_ID.asc()).build();
                assertThat(executor.list(grouped, Limit.unlimited())).extracting(Counted::sales)
                        .containsExactly(1L, 1L, 0L);
            }
            var total = ModelQuery.builder(DAY, row -> new Counted(null, row.get(sales)))
                    .joinSource(COUNTED_SOUTH, sales("south")).select(SelectSet.of(sales)).build();
            assertThat(executor.list(total, Limit.unlimited())).extracting(Counted::sales).containsExactly(2L);
            assertThat(SQL).hasSize(3).allSatisfy(sql -> assertThat(sql).as(sql)
                    .containsPattern("count\\((case when .* then )?\\w+\\.mq_businessDate")
                    .doesNotContain("count(1)").doesNotContain("count(*)"));
            // A count of another joined column counts that column, on either line.
            AggregateField<Counted, Long> cash =
                    Agg.count(ColumnField.over(Counted.class, COUNTED_SOUTH.root(), DS_CASH));
            var counted = ModelQuery.builder(DAY, row -> new Counted(row.get(COUNTED_ID), row.get(cash)))
                    .joinSource(COUNTED_SOUTH, sales("south")).select(SelectSet.of(COUNTED_ID, cash))
                    .groupBy(SelectSet.of(COUNTED_ID)).orderBy(COUNTED_ID.asc()).build();
            assertThat(executor.list(counted, Limit.unlimited())).extracting(Counted::sales)
                    .containsExactly(1L, 1L, 0L);
            assertThat(SQL.get(3)).containsPattern("count\\(\\w+\\.mq_cash\\)");
        });
    }

    @Test
    void ac_agg_29_a_count_of_an_inner_joined_source_is_the_row_count() {
        AggregateField<Counted, Long> sales = Agg.count(COUNTED_NORTH.root());
        var total = ModelQuery.builder(DAY, row -> new Counted(null, row.get(sales)))
                .joinSource(COUNTED_NORTH, sales("north")).select(SelectSet.of(sales)).build();
        executing(executor -> {
            assertThat(executor.list(total, Limit.unlimited())).extracting(Counted::sales).containsExactly(1L);
            assertThat(SQL).singleElement().satisfies(sql -> assertThat(sql).contains("count(*)")
                    .doesNotContain("count(1)"));
        });
    }

    @Test
    void ac_agg_18_first_by_over_a_source_root_appends_the_source_key_to_break_ties() {
        var day = ColumnField.over(Pick.class, SHOP_DAY, SD_DATE);
        var sales = ColumnField.over(Pick.class, SHOP_DAY, SD_SALES);
        // Two rows tie on one sale each, on two days: the key, day then shop, picks the first day.
        AggregateField<Pick, LocalDate> first = Agg.firstBy(day, sales.asc());
        var query = ModelQuery.builder(SHOP_DAY, row -> new Pick(row.get(first)), SHOP_DAYS)
                .select(SelectSet.of(first)).build();
        factory.inSession(em -> {
            SQL.clear();
            var executor = ModelQueryExecutor.create(em, ShopDays.class, ModelQueryConfig.defaults());
            assertThat(executor.list(query, Limit.unlimited())).extracting(Pick::day).containsExactly(FIRST);
            assertThat(SQL).singleElement().satisfies(sql -> assertThat(sql).as(sql)
                    .containsPattern("order by \\w+\\.mq_sales( asc)?, ?\\w+\\.mq_businessDate( asc)?, ?"
                            + "\\w+\\.mq_shop"));
        });
    }

    @Test
    void ac_src_46_a_row_lock_on_a_query_that_joins_a_source_is_mq1903_before_any_statement() {
        var locked = both().build().withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));
        executing(executor -> {
            assertThatThrownBy(() -> executor.list(locked, Limit.of(10)))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1903))
                    .hasMessageContaining("joins a source").hasMessageContaining("withLock");
            assertThat(SQL).isEmpty();
        });
    }

    @Test
    void ac_src_48_a_left_joined_tree_filters_its_walk_in_the_on_and_keeps_every_outer_row() {
        nodes(executor -> {
            List<Leveled> rows = executor.list(leveled(NODES, walk(true, 3, 1L)).build(), Limit.unlimited());

            assertThat(rows).extracting(Leveled::id).containsExactly(1L, 2L, 3L, 4L, 5L, 10L, 11L);
            assertThat(rows).extracting(Leveled::level).containsExactly(0, 1, 2, 3, null, null, null);
            assertThat(SQL).singleElement().satisfies(sql -> assertThat(sql.toLowerCase()).as(sql)
                    // The walk filter, level and cycle mark of the joined CTE, in the join's on (D-187).
                    .startsWith("with recursive").containsPattern(" left join (\\w+) (\\w+) on \\2\\.mq_id=\\w+\\.id "
                            + "and \\2\\.mq_level<=\\? and not\\(?\\2\\.mqcyclemark\\)? order by")
                    // Depth, cycle and duplicate-key checks over the whole walk, in the top selection.
                    .containsPattern("\\(select min\\(\\w+\\.mq_id\\) from \\w+ \\w+ where \\w+\\.mq_level>\\?\\)")
                    .containsPattern("\\(select min\\(\\w+\\.mq_id\\) from \\w+ \\w+ where \\w+\\.mqcyclemark\\)")
                    .contains("having count(1)>?"));
        });
    }

    @Test
    void ac_src_49_a_joined_tree_too_deep_is_mq2701_naming_the_join_for_an_unmatched_outer_row_and_a_count() {
        SourceQuery<Walked> shallow = walk(true, 2, 1L);
        nodes(executor -> {
            assertCode(() -> executor.list(leveled(NODES, shallow).build(), Limit.unlimited()), MqCode.MQ2701,
                    "join 'nodes' of Walked");
            // A LEFT-joined outer row the walk never reaches is a row, and carries the walk's checks.
            assertCode(() -> executor.list(leveled(NODES, shallow).where(f -> f.eq(L_ID, 5L)).build(),
                    Limit.unlimited()), MqCode.MQ2701, "maxDepth(2)");
            assertCode(() -> executor.count(leveled(NODES, shallow).build()), MqCode.MQ2701, "join 'nodes'");
            // An INNER join that keeps no row returns no row, so nothing is checked (R-SRC-14).
            assertThat(executor.list(leveled(NODES_ONLY, shallow).where(f -> f.eq(L_ID, 5L)).build(),
                    Limit.unlimited())).isEmpty();
        });
    }

    @Test
    void ac_src_49_a_joined_tree_over_a_cycle_is_mq2702_and_a_doubly_reached_node_is_mq2703() {
        nodes(executor -> {
            assertCode(() -> executor.list(leveled(NODES, walk(true, 3, 10L)).build(), Limit.unlimited()),
                    MqCode.MQ2702, "join 'nodes' of Walked");
            // Seeds 1 and 2 reach node 2 twice: the key column is the joined walk's, read with no other source.
            assertCode(() -> executor.list(leveled(NODES, walk(true, 3, 1L, 2L)).build(), Limit.unlimited()),
                    MqCode.MQ2703, "key id=2 twice");
        });
    }

    @Test
    void ac_src_50_a_grouped_source_over_a_plain_walk_carries_its_checks_and_aggregates_levels_0_to_max_depth() {
        nodes(executor -> {
            assertThat(executor.list(counted(walk(false, 3, 1L)), Limit.unlimited())).extracting(Counts::children)
                    .containsExactly(1L, 1L, 1L, null, null, null, null);
            // The inner walk's filter stays in the grouped body, its checks in the joining statement (R-SRC-18).
            assertThat(SQL).singleElement().satisfies(sql -> assertThat(sql.toLowerCase()).as(sql)
                    .containsPattern("where \\w+\\.mq_level<=\\? and not\\(?\\w+\\.mqcyclemark\\)? group by")
                    .containsPattern("\\(select min\\(\\w+\\.mq_id\\) from \\w+ \\w+ where \\w+\\.mq_level>\\?\\)")
                    .containsPattern("\\(select min\\(\\w+\\.mq_id\\) from \\w+ \\w+ where \\w+\\.mqcyclemark\\)")
                    .doesNotContain("having count(1)>?"));
            assertCode(() -> executor.list(counted(walk(false, 2, 1L)), Limit.unlimited()), MqCode.MQ2701,
                    "join 'kids' of Kids");
            assertCode(() -> executor.list(counted(walk(false, 3, 10L)), Limit.unlimited()), MqCode.MQ2702,
                    "join 'kids' of Kids");
        });
    }

    @Test
    void ac_src_49_the_treated_row_count_selects_the_joined_walk_s_checks_beside_its_count() {
        RenderOptions options = RenderOptions.portable().withSourceRenderer(HibernateSourceRenderer.INSTANCE);
        factory.inSession(em -> {
            for (int maxDepth : new int[] {3, 2}) {
                BuiltQuery<Leveled> built = leveled(NODES, walk(true, maxDepth, 1L)).build()
                        .buildRootIdCount(em.getCriteriaBuilder(), options);
                assertThat(built.recursionChecks()).hasSize(3);
                assertThat(built.recursionJoin()).hasValue("join 'nodes' of Walked");
                assertThat(built.sourceMaxDepth()).isEqualTo(maxDepth);
                assertThat(built.sourceKeyColumns()).containsExactly("id");
                Tuple counted = em.createQuery(built.query()).getSingleResult();
                assertThat(((Number) counted.get(0)).longValue()).isEqualTo(7L);
                // The first key past maxDepth 2 is node 4, at level 3.
                assertThat(counted.get(BuiltQuery.RECURSION_DEPTH)).isEqualTo(maxDepth == 3 ? null : 4L);
            }
        });
    }

    @Test
    void ac_src_49_a_through_child_load_joining_a_walk_carries_its_checks() {
        nodes(executor -> {
            for (int maxDepth : new int[] {3, 2}) {
                var plan = FetchPlan.of(SelectSet.of(P_ID)).child(kids(walk(true, maxDepth, 1L)),
                        FetchPlan.of(SelectSet.of(L_ID, L_LEVEL)));
                var parents = ModelQuery.builder(NODE, row -> new Parent(row.get(P_ID), List.of()))
                        .select(SelectSet.of(P_ID)).primaryKey(PrimaryKey.of(P_ID)).orderBy(P_ID.asc())
                        .where(f -> f.eq(P_ID, 1L)).fetch(plan).build();
                if (maxDepth == 3) {
                    assertThat(executor.list(parents, Limit.unlimited())).singleElement()
                            .satisfies(parent -> assertThat(parent.kids()).containsExactly(new Leveled(2L, 1)));
                } else {
                    assertCode(() -> executor.list(parents, Limit.unlimited()), MqCode.MQ2701,
                            "join 'nodes' of Walked");
                }
            }
        });
    }

    @Test
    void ac_src_43_a_fetch_plan_whose_enricher_reads_a_joined_column_runs() {
        var plan = FetchPlan.of(SelectSet.of(ID, SOUTH_CASH)).enrich(Enricher.of(UnaryOperator.identity(), NORTH_CASH));
        executing(executor -> {
            List<View> rows = executor.list(both().fetch(plan).build(), Limit.unlimited());

            assertThat(rows).extracting(View::id).containsExactly(1L, 2L, 3L);
            assertThat(rows.get(0).north()).isEqualByComparingTo("15");
            assertThat(rows.get(0).south()).isEqualByComparingTo("7");
            assertThat(rows.get(1).north()).isNull();
        });
    }

    @Test
    void ac_src_43_a_line_without_the_cte_join_methods_refuses_a_joined_source_with_mq4014() {
        assertThat(HibernateSourceRenderer.CteJoin.of(JpaFrom.class)).isPresent();
        assertThat(HibernateSourceRenderer.CteJoin.of(Object.class)).isEmpty();
        assertThat(HibernateSourceRenderer.CteJoin.of(JoinWithoutOn.class)).isEmpty();

        var renderer = new HibernateSourceRenderer(Optional.empty());

        assertThatThrownBy(() -> renderer.join(null, null, JoinType.LEFT, joined -> null))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                .hasMessageContaining("joined sources");
    }

    /** A parent's children through its {@code children} association, each with its level in {@code walk}. */
    private static ChildField<Parent, Leveled> kids(SourceQuery<Walked> walk) {
        TableField<Node, Node> through = TableField.join(NODE, "children", JoinType.INNER);
        return new ChildField<>() {
            @Override
            public String name() {
                return "kids";
            }

            @Override
            public ColumnField<Parent, ?, ?> key() {
                return P_ID;
            }

            @Override
            public Optional<ColumnField<Leveled, ?, ?>> foreignKey() {
                return Optional.empty();
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return Optional.of(through);
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, Leveled> query() {
                return leveled(NODES, walk);
            }

            @Override
            public Parent with(Parent parent, List<Leveled> children) {
                return new Parent(parent.id(), children);
            }
        };
    }

    private static void assertCode(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(code)).hasMessageContaining(message);
    }

    private static void nodes(Consumer<ModelQueryExecutor<Node>> work) {
        factory.inSession(em -> {
            SQL.clear();
            work.accept(ModelQueryExecutor.create(em, Node.class, ModelQueryConfig.defaults()));
        });
    }

    private static void executing(Consumer<ModelQueryExecutor<Day>> work) {
        factory.inSession(em -> {
            SQL.clear();
            work.accept(ModelQueryExecutor.create(em, Day.class, ModelQueryConfig.defaults()));
        });
    }

    private static int occurrences(String sql, String part) {
        int count = 0;
        for (int at = sql.indexOf(part); at >= 0; at = sql.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
