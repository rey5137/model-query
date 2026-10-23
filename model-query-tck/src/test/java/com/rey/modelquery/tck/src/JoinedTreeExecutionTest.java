package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CompositeNodeEntity;
import com.rey.modelquery.tck.col.FolderNodeEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.VendorSources;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * A query rooted at {@code folder_nodes} joining a recursive source over the same table: a {@code tree(...)} walk and
 * a grouped source over a plain {@code recursive(...)} one. The walk's filter rides the join's {@code on}, its depth,
 * cycle and duplicate-key checks every statement's selection, and they refuse whole even an outer row the walk never
 * reached (spec api/17 R-SRC-08, R-SRC-14, R-SRC-15, R-SRC-18, R-SRC-21, R-SRC-22, AC-SRC-48 to AC-SRC-50; D-187).
 */
class JoinedTreeExecutionTest {

    record Leveled(Long id, Integer level) {}

    record Reached(Long id, Long reached) {}

    record Counts(Long id, Long children) {}

    record Parent(Long id, List<Leveled> parents) {}

    record Node(Integer tenantId, Long id, Integer level) {}

    /** A grouped source over the walk: its nodes per parent. */
    static final class Kids {}

    private static final TableField<FolderNodeEntity, FolderNodeEntity> FOLDERS =
            TableField.root(FolderNodeEntity.class);
    private static final OrderedColumnField<Leveled, FolderNodeEntity, Long> L_ID =
            ColumnField.of(Leveled.class, FOLDERS, "id", Long.class).named("id");
    private static final OrderedColumnField<Leveled, FolderNodeEntity, Long> L_PARENT =
            ColumnField.of(Leveled.class, FOLDERS, "parentId", Long.class);
    private static final SourceJoin<Leveled, FolderNode> NODES = SourceJoin.of("nodes", FolderNode.class,
            JoinKind.LEFT, List.of(SourceJoin.on(L_ID, QFolderNode.ID)));
    private static final SourceJoin<Leveled, FolderNode> NODES_ONLY = SourceJoin.of("nodes", FolderNode.class,
            JoinKind.INNER, List.of(SourceJoin.on(L_ID, QFolderNode.ID)));
    private static final OrderedColumnField<Leveled, FolderNode, Integer> L_LEVEL =
            ColumnField.over(Leveled.class, NODES.root(), QFolderNode.LEVEL).named("level");

    private static final TableField<FolderNode, FolderNode> WALKED = TableField.source(FolderNode.class);
    private static final ColumnField<Kids, FolderNode, Long> K_PARENT =
            ColumnField.over(Kids.class, WALKED, QFolderNode.PARENT_ID).named("parentId");
    private static final AggregateField<Kids, Long> K_CHILDREN = Agg.<Kids>count(WALKED).named("children");
    private static final OrderedColumnField<Counts, FolderNodeEntity, Long> C_ID =
            ColumnField.of(Counts.class, FOLDERS, "id", Long.class).named("id");
    private static final SourceJoin<Counts, Kids> KIDS = SourceJoin.of("kids", Kids.class, JoinKind.LEFT,
            List.of(SourceJoin.on(C_ID, K_PARENT)));
    private static final ColumnField<Counts, Kids, Long> C_CHILDREN =
            ColumnField.over(Counts.class, KIDS.root(), K_CHILDREN).named("children");

    private static final OrderedColumnField<Parent, FolderNodeEntity, Long> P_ID =
            ColumnField.of(Parent.class, FOLDERS, "id", Long.class).named("id");
    private static final SourceJoin<Parent, FolderNode> P_NODES = SourceJoin.of("nodes", FolderNode.class,
            JoinKind.LEFT, List.of(SourceJoin.on(P_ID, QFolderNode.ID)));

    /** The outer rows the joining reads keep: the tree 1 to 6 and the chain 10 to 15. */
    private static final long LAST_NODE = 15;

    /** The checked walk from {@code seeds} down {@code parent_id} (R-SRC-13). */
    private static SourceQuery<FolderNode> tree(int maxDepth, Long... seeds) {
        return QFolderNode.source().where(f -> f.in(QFolderNode.ID, List.of(seeds)))
                .tree(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
    }

    /** The plain walk from {@code seeds}, which only a grouped source over it may join (R-SRC-21). */
    private static SourceQuery<FolderNode> walk(int maxDepth, Long... seeds) {
        return QFolderNode.source().where(f -> f.in(QFolderNode.ID, List.of(seeds)))
                .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth)
                .build();
    }

    /** Nodes 1 to {@link #LAST_NODE} with their level in {@code walk}, joined {@code join}. */
    private static ModelQuery.Builder<FolderNodeEntity, Long, Leveled> leveled(SourceJoin<Leveled, FolderNode> join,
            SourceQuery<FolderNode> walk) {
        return ModelQuery.builder(FOLDERS, row -> new Leveled(row.get(L_ID), row.get(L_LEVEL)))
                .joinSource(join, walk).select(SelectSet.of(L_ID, L_LEVEL)).primaryKey(PrimaryKey.of(L_ID))
                .where(f -> f.lte(L_ID, LAST_NODE)).orderBy(L_ID.asc());
    }

    /** Nodes 1 to {@link #LAST_NODE} with their children in {@code walk}, counted by a grouped source over it. */
    private static ModelQuery<FolderNodeEntity, Long, Counts> counted(SourceQuery<FolderNode> walk) {
        var kids = SourceQuery.builder(Kids.class, WALKED, SelectSet.of(K_PARENT, K_CHILDREN),
                PrimaryKey.of(K_PARENT), SelectSet.of(K_PARENT), walk).build();
        return ModelQuery.builder(FOLDERS, row -> new Counts(row.get(C_ID), row.get(C_CHILDREN)))
                .joinSource(KIDS, kids).select(SelectSet.of(C_ID, C_CHILDREN)).primaryKey(PrimaryKey.of(C_ID))
                .where(f -> f.lte(C_ID, LAST_NODE)).orderBy(C_ID.asc()).build();
    }

    // ---- AC-SRC-48

    @TckTest
    void ac_src_48_a_left_joined_tree_filters_its_walk_in_the_on_and_selects_its_three_checks(TckDatabase db) {
        List<Leveled> rows = new ArrayList<>();
        long[] counted = new long[1];
        List<String> sql = SqlSnapshots.assertMatches(db, "src-48-joined-tree", ds -> withExecutor(ds, executor -> {
            rows.addAll(executor.list(leveled(NODES, tree(3, 1L)).build(), Limit.unlimited()));
            counted[0] = executor.count(leveled(NODES, tree(3, 1L)).build());
        }));

        assertThat(rows).extracting(Leveled::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 10L, 11L, 12L, 13L, 14L,
                15L);
        assertThat(rows).extracting(Leveled::level).containsExactly(0, 1, 1, 2, 2, 2, null, null, null, null, null,
                null);
        assertThat(counted[0]).isEqualTo(12L);
        assertThat(sql).hasSize(2).allSatisfy(statement -> {
            String lower = statement.toLowerCase(Locale.ROOT);
            // A recursive CTE is never inlined, H2 included: the walk is joined, and filtered in the join's on.
            assertThat(lower).as(statement).startsWith("with recursive").contains(" left join ")
                    .containsPattern(" on \\w+\\.mq_id ?= ?\\w+\\.id and \\w+\\.mq_level ?<= ?\\?"
                            + " and not ?\\(?\\w+\\.mqcyclemark");
            // The depth and cycle checks, and the tree's duplicate-key check, over the whole walk.
            assertThat(SourceJoinExecutionTest.occurrences(lower, "(select min(")).as(statement)
                    .isGreaterThanOrEqualTo(2);
            assertThat(lower).as(statement).contains("having count(");
        });
    }

    // ---- AC-SRC-49

    @TckTest
    void ac_src_49_every_statement_kind_raises_mq2701_mq2702_and_mq2703_through_a_joined_tree(TckDatabase db) {
        record Shape(SourceQuery<FolderNode> walk, MqCode code, String message) {}
        List<Shape> shapes = List.of(new Shape(tree(1, 1L), MqCode.MQ2701, "maxDepth(1)"),
                new Shape(tree(5, 100L), MqCode.MQ2702, "cycle"),
                new Shape(tree(3, 1L, 2L), MqCode.MQ2703, "key id=2 twice"));
        SqlSnapshots.capture(db, ds -> withTransaction(ds, executor -> {
            for (Shape shape : shapes) {
                var plain = leveled(NODES, shape.walk()).build();
                var keyset = leveled(NODES, shape.walk()).keyset().build();
                var pkFirst = leveled(NODES, shape.walk()).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
                var grouped = ModelQuery.builder(FOLDERS, row -> new Leveled(null, row.get(L_LEVEL)))
                        .joinSource(NODES, shape.walk()).select(SelectSet.of(L_LEVEL)).groupBy(SelectSet.of(L_LEVEL))
                        .build();
                List<Runnable> reads = new ArrayList<>(List.of(
                        () -> executor.list(plain, Limit.unlimited()),
                        () -> executor.count(plain),
                        () -> executor.count(grouped),
                        () -> executor.page(plain, PageSpec.of(0, 5), CountMode.COUNT),
                        () -> executor.page(plain, PageSpec.ofOffset(3, 5), CountMode.NO_COUNT),
                        () -> executor.page(pkFirst, PageSpec.ofOffset(3, 5), CountMode.NO_COUNT),
                        () -> executor.page(keyset, KeysetSpec.first(5)),
                        () -> executor.one(plain),
                        () -> executor.one(plain, 12L),
                        () -> executor.byKeys(plain, List.of(12L, 13L)),
                        () -> executor.list(parents(true, shape.walk()), Limit.unlimited()),
                        () -> executor.list(parents(false, shape.walk()), Limit.unlimited())));
                for (var query : List.of(plain, keyset, pkFirst)) {
                    reads.add(() -> {
                        List<Leveled> delivered = new ArrayList<>();
                        try {
                            executor.export(query, ExportOptions.of(5), page -> page, delivered::add);
                        } finally {
                            assertThat(delivered).isEmpty();
                        }
                    });
                }
                reads.add(() -> {
                    List<Leveled> delivered = new ArrayList<>();
                    try {
                        executor.stream(plain, Limit.unlimited(), stream -> {
                            stream.forEach(delivered::add);
                            return delivered.size();
                        });
                    } finally {
                        assertThat(delivered).isEmpty();
                    }
                });
                for (int read = 0; read < reads.size(); read++) {
                    assertThat(refusal(reads.get(read), shape.code())).as("read %d of %s", read, shape.code())
                            .hasMessageContaining("join 'nodes' of FolderNode")
                            .hasMessageContaining(shape.message());
                }
            }
        }));
    }

    @TckTest
    void ac_src_49_a_left_joined_row_the_walk_never_reached_still_raises_and_an_empty_inner_reads_nothing(
            TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            // Node 15 is on the chain, which the walk from 1 never reaches: under LEFT it is a row, and checked.
            var unreached = leveled(NODES, tree(1, 1L)).where(f -> f.eq(L_ID, 15L)).build();
            assertThat(refusal(() -> executor.list(unreached, Limit.unlimited()), MqCode.MQ2701))
                    .hasMessageContaining("maxDepth(1)");
            // Under INNER no row is left, and an empty result carries no check (R-SRC-14); a count is a row.
            var empty = leveled(NODES_ONLY, tree(1, 1L)).where(f -> f.eq(L_ID, 15L)).build();
            assertThat(executor.list(empty, Limit.unlimited())).isEmpty();
            refusal(() -> executor.count(empty), MqCode.MQ2701);
        }));
    }

    @TckTest
    void ac_src_49_the_duplicate_check_precedes_mq2003_mq2005_and_mq2202(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            // A healthy walk: one(q) over many rows, and a keyset over a NULL order value, refuse as without a join.
            refusal(() -> executor.one(leveled(NODES, tree(3, 1L)).build()), MqCode.MQ2003);
            var nullOrder = ModelQuery.builder(FOLDERS, row -> new Leveled(row.get(L_ID), row.get(L_LEVEL)))
                    .joinSource(NODES, tree(3, 1L)).select(SelectSet.of(L_ID, L_LEVEL))
                    .primaryKey(PrimaryKey.of(L_ID)).where(f -> f.lte(L_ID, LAST_NODE))
                    .orderBy(L_PARENT.asc(), L_ID.asc()).keyset();
            refusal(() -> executor.page(nullOrder.build(), KeysetSpec.first(20)), MqCode.MQ2202);
            // Seeds 1 and 2 reach node 2 twice: that is read first.
            refusal(() -> executor.one(leveled(NODES, tree(3, 1L, 2L)).build()), MqCode.MQ2703);
            var duplicateNullOrder = ModelQuery.builder(FOLDERS,
                            row -> new Leveled(row.get(L_ID), row.get(L_LEVEL)))
                    .joinSource(NODES, tree(3, 1L, 2L)).select(SelectSet.of(L_ID, L_LEVEL))
                    .primaryKey(PrimaryKey.of(L_ID)).where(f -> f.lte(L_ID, LAST_NODE))
                    .orderBy(L_PARENT.asc(), L_ID.asc()).keyset().build();
            refusal(() -> executor.page(duplicateNullOrder, KeysetSpec.first(20)), MqCode.MQ2703);
        }));
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db, DecimalFolder.class)) {
            sf.inSession(em -> {
                var executor = ModelQueryExecutor.create(em, DecimalFolder.class, ModelQueryConfig.defaults());
                var scaledKey = new BigDecimal("2.0");
                refusal(() -> executor.byKeys(decimalKeys(tree(3, 1L)), List.of(scaledKey)), MqCode.MQ2005);
                refusal(() -> executor.byKeys(decimalKeys(tree(3, 1L, 2L)), List.of(scaledKey)), MqCode.MQ2703);
            });
        }
    }

    @TckTest
    void ac_src_49_a_count_of_a_left_joined_tree_is_0_for_a_node_it_does_not_reach(TckDatabase db) {
        var id = ColumnField.of(Reached.class, FOLDERS, "id", Long.class).named("id");
        var nodes = SourceJoin.of("nodes", FolderNode.class, JoinKind.LEFT, List.of(SourceJoin.on(id, QFolderNode.ID)));
        AggregateField<Reached, Long> reached = Agg.count(nodes.root());
        var perNode = ModelQuery.builder(FOLDERS, row -> new Reached(row.get(id), row.get(reached)))
                .joinSource(nodes, tree(3, 1L)).select(SelectSet.of(id, reached)).groupBy(SelectSet.of(id))
                .where(f -> f.lte(id, LAST_NODE)).orderBy(id.asc()).build();
        List<Reached> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor ->
                rows.addAll(executor.list(perNode, Limit.unlimited()))));

        assertThat(rows).extracting(Reached::reached).containsExactly(1L, 1L, 1L, 1L, 1L, 1L, 0L, 0L, 0L, 0L, 0L,
                0L);
    }

    // ---- AC-SRC-50

    @TckTest
    void ac_src_50_a_grouped_source_over_a_plain_walk_aggregates_levels_0_to_max_depth_and_carries_its_checks(
            TckDatabase db) {
        List<Counts> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-50-grouped-walk", ds -> withExecutor(ds,
                executor -> rows.addAll(executor.list(counted(walk(3, 1L)), Limit.unlimited()))));

        // 1 has 2 and 3; 2 has 4 and 5; 3 has 6; the leaves and the chain have none.
        assertThat(rows).extracting(Counts::children).containsExactly(2L, 2L, 1L, null, null, null, null, null, null,
                null, null, null);
        assertThat(sql).singleElement().satisfies(statement -> assertThat(statement.toLowerCase(Locale.ROOT))
                .as(statement).startsWith("with recursive")
                // The walk's filter stays in the grouped body (R-SRC-18), its checks ride the joining statement.
                .containsPattern("where \\w+\\.mq_level ?<= ?\\?")
                .doesNotContain("having count("));
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            refusal(() -> executor.list(counted(walk(1, 1L)), Limit.unlimited()), MqCode.MQ2701);
            refusal(() -> executor.count(counted(walk(1, 1L))), MqCode.MQ2701);
            assertThat(refusal(() -> executor.list(counted(walk(5, 100L)), Limit.unlimited()), MqCode.MQ2702))
                    .hasMessageContaining("join 'kids' of Kids");
        }));
    }

    @TckTest
    void ac_src_50_a_second_walk_across_the_joined_chains_is_mq1910_before_any_statement(TckDatabase db) {
        var kids = SourceQuery.builder(Kids.class, WALKED, SelectSet.of(K_PARENT, K_CHILDREN),
                PrimaryKey.of(K_PARENT), SelectSet.of(K_PARENT), walk(3, 1L)).build();
        var id = ColumnField.of(Counts.class, FOLDERS, "id", Long.class).named("id");
        var nodes = SourceJoin.of("nodes", FolderNode.class, JoinKind.LEFT, List.of(SourceJoin.on(id, QFolderNode.ID)));
        var kidsJoin = SourceJoin.of("kids", Kids.class, JoinKind.LEFT, List.of(SourceJoin.on(id, K_PARENT)));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            Throwable thrown = catchThrowable(() -> ModelQuery.builder(FOLDERS, row -> new Counts(row.get(id), null))
                    .joinSource(nodes, tree(3, 1L)).joinSource(kidsJoin, kids).select(SelectSet.of(id)).build());
            assertThat(thrown).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1910)).hasMessageContaining("walk 2 recursive");
        }));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_50_h2_refuses_a_joined_tree_over_a_composite_node_key_before_any_statement(TckDatabase db) {
        Assumptions.assumeTrue(VendorSources.refusesCompositeNodeKey(db),
                "H2 rejects the multi-column cycle clause; PostgreSQL and MySQL render it (RFC 0014 §7)");
        Throwable[] refusal = new Throwable[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> refusal[0] = catchThrowable(() -> ModelQueryExecutor
                        .create(em, CompositeNodeEntity.class, ModelQueryConfig.defaults())
                        .list(compositeTree(), Limit.unlimited())));
            }
        });

        assertThat(refusal[0]).isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015));
        assertThat(refusal[0]).hasMessageContaining("RECURSIVE_COMPOSITE_KEY");
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_50_postgres_and_mysql_join_a_tree_over_a_composite_node_key(TckDatabase db) {
        Assumptions.assumeFalse(VendorSources.refusesCompositeNodeKey(db),
                "H2 refuses a composite node key with MQ4015");
        List<Node> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor
                        .create(em, CompositeNodeEntity.class, ModelQueryConfig.defaults())
                        .list(compositeTree(), Limit.unlimited())));
            }
        });

        assertThat(rows.stream().map(row -> row.tenantId() + "|" + row.id() + "|" + row.level()).toList())
                .containsExactly("1|1|0", "1|2|1", "1|3|1", "1|4|2", "1|5|2", "1|6|2");
    }

    /** Every composite node with its level in a tree from node 1, joined LEFT on both key columns. */
    private static ModelQuery<CompositeNodeEntity, ?, Node> compositeTree() {
        var nodes = TableField.root(CompositeNodeEntity.class);
        var tenant = ColumnField.of(Node.class, nodes, "tenantId", Integer.class).named("tenantId");
        var id = ColumnField.of(Node.class, nodes, "id", Long.class).named("id");
        var walk = SourceJoin.of("walk", CompositeNode.class, JoinKind.LEFT,
                List.of(SourceJoin.on(tenant, QCompositeNode.TENANT_ID), SourceJoin.on(id, QCompositeNode.ID)));
        var level = ColumnField.over(Node.class, walk.root(), QCompositeNode.LEVEL).named("level");
        SourceQuery<CompositeNode> tree = QCompositeNode.source().where(f -> f.eq(QCompositeNode.ID, 1L))
                .tree(step -> step.childKey(QCompositeNode.PARENT_ID).parentKey(QCompositeNode.ID), 3).build();
        return ModelQuery.builder(nodes, row -> new Node(row.get(tenant), row.get(id), row.get(level)))
                .joinSource(walk, tree).select(SelectSet.of(tenant, id, level)).orderBy(tenant.asc(), id.asc())
                .build();
    }

    // ---- helpers

    /**
     * Node 203 or every node to {@link #LAST_NODE}, with the nodes its {@code parents} association reaches, each with
     * its level in {@code walk}: the child load joins the walk, and the parent read too when {@code joined}.
     */
    private static ModelQuery<FolderNodeEntity, Long, Parent> parents(boolean joined, SourceQuery<FolderNode> walk) {
        var plan = FetchPlan.of(SelectSet.of(P_ID)).child(parentsOf(walk), FetchPlan.of(SelectSet.of(L_ID, L_LEVEL)));
        var builder = ModelQuery.builder(FOLDERS, row -> new Parent(row.get(P_ID), List.of()))
                .select(SelectSet.of(P_ID)).primaryKey(PrimaryKey.of(P_ID)).orderBy(P_ID.asc()).fetch(plan);
        return (joined ? builder.joinSource(P_NODES, walk).where(f -> f.lte(P_ID, LAST_NODE))
                : builder.where(f -> f.eq(P_ID, 203L))).build();
    }

    /** A parent's {@code parents}, through the association, each with its level in {@code walk}. */
    private static ChildField<Parent, Leveled> parentsOf(SourceQuery<FolderNode> walk) {
        TableField<FolderNodeEntity, FolderNodeEntity> through = TableField.join(FOLDERS, "parents", JoinType.INNER);
        return new ChildField<>() {
            @Override
            public String name() {
                return "parents";
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
                return ModelQuery.builder(FOLDERS, row -> new Leveled(row.get(L_ID), row.get(L_LEVEL)))
                        .joinSource(NODES, walk).select(SelectSet.of(L_ID, L_LEVEL)).primaryKey(PrimaryKey.of(L_ID))
                        .orderBy(L_ID.asc());
            }

            @Override
            public Parent with(Parent parent, List<Leveled> children) {
                return new Parent(parent.id(), children);
            }
        };
    }

    /** Nodes with their decimal ids joined to {@code walk}, so a key of another scale is {@code MQ2005}. */
    private static ModelQuery<DecimalFolder, BigDecimal, Leveled> decimalKeys(SourceQuery<FolderNode> walk) {
        var root = TableField.root(DecimalFolder.class);
        var id = ColumnField.of(Leveled.class, root, "id", BigDecimal.class).named("id");
        var longId = ColumnField.of(Leveled.class, root, "longId", Long.class);
        var nodes = SourceJoin.of("nodes", FolderNode.class, JoinKind.LEFT,
                List.of(SourceJoin.on(longId, QFolderNode.ID)));
        var level = ColumnField.over(Leveled.class, nodes.root(), QFolderNode.LEVEL).named("level");
        return ModelQuery.builder(root, row -> new Leveled(row.get(id).longValueExact(), row.get(level)))
                .joinSource(nodes, walk).select(SelectSet.of(id, level)).primaryKey(PrimaryKey.of(id)).build();
    }

    /** Reads the integer id as a decimal too, so SQL equality can match a key whose Java scale differs. */
    @Entity(name = "JoinedDecimalFolder")
    @Table(name = "folder_nodes")
    static class DecimalFolder {
        @Id
        BigDecimal id;

        @Column(name = "id", insertable = false, updatable = false)
        Long longId;
    }

    private static ModelQueryException refusal(Runnable read, MqCode code) {
        Throwable thrown = catchThrowable(read::run);
        assertThat(thrown).as("expected %s", code).isInstanceOf(ModelQueryException.class);
        assertThat(((ModelQueryException) thrown).code()).as(thrown.getMessage()).isEqualTo(code);
        if (code.name().startsWith("MQ27")) {
            assertThat(thrown).isInstanceOf(ModelQueryExecutionException.class);
        }
        return (ModelQueryException) thrown;
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<FolderNodeEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, FolderNodeEntity.class, ModelQueryConfig.defaults())));
        }
    }

    /** As {@link #withExecutor(DataSource, Consumer)}, in a transaction, as a stream needs (R-EXE-08). */
    private static void withTransaction(DataSource ds, Consumer<ModelQueryExecutor<FolderNodeEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.fromTransaction(em -> {
                work.accept(ModelQueryExecutor.create(em, FolderNodeEntity.class, ModelQueryConfig.defaults()));
                return null;
            });
        }
    }
}
