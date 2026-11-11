package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
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
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.FolderNodeEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.JoinType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Joined sources anchored on a column-anchored entity join: created after every column-anchored join, attached to the
 * statement's root, on every Tier-1 target and statement, rows compared with native SQL (R-SRC-21, R-SRC-24,
 * R-SRC-25, D-218, AC-SRC-66, AC-SRC-68, AC-SRC-69).
 */
class SourceOnEntityJoinExecutionTest {

    /** A grouped source over the orders: how many orders each customer up to 900 referred. */
    static final class Referrals {}

    /** A source over the customers keyed by their e-mail, of three countries. */
    static final class Contacts {}

    record Row(Long id, String referred, Long referrals) {}

    record Contact(Long id, String country) {}

    record Top(Long id, Long referrals, String top) {}

    record Group(Long referrals, Long counted) {}

    record Leveled(Long id, Integer parentLevel) {}

    record Parent(Long id, List<Row> orders) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);

    private static final ColumnField<Referrals, OrderEntity, Long> S_REFERRER =
            ColumnField.of(Referrals.class, ORDERS, "referrerId", Long.class).named("referrerId");
    private static final AggregateField<Referrals, Long> S_COUNT = Agg.<Referrals>count(ORDERS).named("referrals");
    private static final SourceQuery<Referrals> REFERRALS = SourceQuery.builder(Referrals.class, ORDERS,
            SelectSet.of(S_REFERRER, S_COUNT), PrimaryKey.of(S_REFERRER), SelectSet.of(S_REFERRER))
            .where(f -> f.lte(S_REFERRER, 900L)).build();
    private static final String NATIVE_REFERRALS =
            "(select referrer_id, count(*) n from orders where referrer_id <= 900 group by referrer_id)";

    private static final ColumnField<Contacts, CustomerEntity, String> S_EMAIL =
            ColumnField.of(Contacts.class, CUSTOMERS, "email", String.class).named("email");
    private static final ColumnField<Contacts, CustomerEntity, String> S_COUNTRY =
            ColumnField.of(Contacts.class, CUSTOMERS, "country", String.class).named("country");
    private static final SourceQuery<Contacts> CONTACTS = SourceQuery.builder(Contacts.class, CUSTOMERS,
            SelectSet.of(S_EMAIL, S_COUNTRY), PrimaryKey.of(S_EMAIL))
            .where(f -> f.in(S_COUNTRY, List.of("DE", "FR", "JP"))).build();

    private static final OrderedColumnField<Row, OrderEntity, Long> ID =
            ColumnField.of(Row.class, ORDERS, "id", Long.class).named("id");
    private static final EntityJoin<Row, CustomerEntity> REFERRED = referred(Row.class, JoinKind.LEFT);
    private static final OrderedColumnField<Row, CustomerEntity, String> NAME =
            ColumnField.of(Row.class, REFERRED.root(), "name", String.class).named("referred");
    private static final SourceJoin<Row, Referrals> SOURCE = referrals(Row.class, REFERRED, JoinKind.LEFT);
    private static final ColumnField<Row, Referrals, Long> COUNT =
            ColumnField.over(Row.class, SOURCE.root(), S_COUNT).named("referrals");

    /** The orders 990 to 1030: every third has a referrer, of whom the source keeps those up to 900. */
    private static final String NATIVE_ROWS = """
            select o.id, r.name, k.n from orders o
            %s join customers r on r.id = o.referrer_id
            %s join %s k on k.referrer_id = r.id
            where o.id between 990 and 1030 order by o.id""";

    private static <M> EntityJoin<M, CustomerEntity> referred(Class<M> model, JoinKind kind) {
        return EntityJoin.of("referred", CustomerEntity.class, kind,
                List.of(EntityJoin.on(ColumnField.of(model, ORDERS, "referrerId", Long.class), "id")));
    }

    /** The referrals of the customer {@code join} reads, anchored on its id. */
    private static <M> SourceJoin<M, Referrals> referrals(Class<M> model, EntityJoin<M, CustomerEntity> join,
            JoinKind kind) {
        return SourceJoin.of("referrals", Referrals.class, kind,
                List.of(SourceJoin.on(ColumnField.of(model, join.root(), "id", Long.class), S_REFERRER)));
    }

    /** The source is bound before the entity join it reads: the build order, not the binding order, places it. */
    private static ModelQuery.Builder<OrderEntity, Long, Row> rows(EntityJoin<Row, CustomerEntity> referred,
            SourceJoin<Row, Referrals> source) {
        var name = ColumnField.of(Row.class, referred.root(), "name", String.class).named("referred");
        var count = ColumnField.over(Row.class, source.root(), S_COUNT).named("referrals");
        return ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), row.get(name), row.get(count)))
                .joinSource(source, REFERRALS).joinEntity(referred).select(SelectSet.of(ID, name, count))
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
    }

    private static ModelQuery.Builder<OrderEntity, Long, Row> rows() {
        return rows(REFERRED, SOURCE);
    }

    // ---- AC-SRC-66: cell 1, LEFT on LEFT, every statement

    @TckTest
    void ac_src_66_a_left_source_on_a_left_entity_join_matches_native_sql(TckDatabase db) throws SQLException {
        List<Row> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x ->
                rows.addAll(x.list(rows().build(), Limit.unlimited()))));
        assertThat(text(rows)).containsExactlyElementsOf(SourceJoinExecutionTest.rows(db,
                NATIVE_ROWS.formatted("left", "left", NATIVE_REFERRALS)));
        assertThat(rows).hasSize(41)
                .anySatisfy(r -> assertThat(r.id()).isEqualTo(990L))
                .anySatisfy(r -> assertThat(r).isEqualTo(new Row(993L, "Customer 0924", null)))
                .anySatisfy(r -> assertThat(r).isEqualTo(new Row(991L, null, null)));
        assertThat(rows).filteredOn(r -> r.referrals() != null).hasSize(11);
        assertThat(sql).singleElement().satisfies(statement ->
                assertThat(joined(statement)).as(statement).containsExactly("customers", "source"));
    }

    @TckTest
    void ac_src_66_every_statement_creates_the_entity_join_before_the_source(TckDatabase db) {
        var plain = rows().build();
        var keyset = rows().keyset().build();
        var pkFirst = rows().primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0L)).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            List<Row> all = x.list(plain, Limit.unlimited());
            assertThat(x.page(plain, PageSpec.of(0, 7), CountMode.COUNT).total()).hasValue(41L);
            assertThat(x.count(plain)).isEqualTo(41L);
            assertThat(x.page(plain, PageSpec.ofOffset(7, 7), CountMode.NO_COUNT).content())
                    .containsExactlyElementsOf(all.subList(7, 14));
            assertThat(x.page(pkFirst, PageSpec.ofOffset(7, 7), CountMode.NO_COUNT).content())
                    .containsExactlyElementsOf(all.subList(7, 14));
            List<Row> paged = new ArrayList<>();
            var page = x.page(keyset, KeysetSpec.first(7));
            paged.addAll(page.content());
            while (page.nextCursor().isPresent()) {
                page = x.page(keyset, KeysetSpec.after(page.nextCursor().get(), 7));
                paged.addAll(page.content());
            }
            assertThat(paged).containsExactlyElementsOf(all);
            assertThat(x.byKeys(plain, List.of(990L, 993L, 1030L)).values())
                    .containsExactlyInAnyOrder(all.get(0), all.get(3), all.get(40));
            assertThat(x.one(plain, 990L)).contains(all.get(0));
            for (var query : List.of(plain, keyset, pkFirst)) {
                List<Row> exported = new ArrayList<>();
                x.export(query, ExportOptions.of(13), p -> p, exported::add);
                assertThat(exported).containsExactlyElementsOf(all);
            }
        }));
        assertThat(sql).hasSizeGreaterThan(20).allSatisfy(statement ->
                assertThat(joined(statement)).as(statement).containsExactly("customers", "source"));
    }

    // ---- AC-SRC-66: cell 2, INNER on INNER

    @TckTest
    void ac_src_66_an_inner_source_on_an_inner_entity_join_drops_the_native_rows(TckDatabase db) throws SQLException {
        var referred = referred(Row.class, JoinKind.INNER);
        var query = rows(referred, referrals(Row.class, referred, JoinKind.INNER)).build();
        List<String> expected = SourceJoinExecutionTest.rows(db,
                NATIVE_ROWS.formatted("inner", "inner", NATIVE_REFERRALS));
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            List<Row> rows = x.list(query, Limit.unlimited());
            assertThat(text(rows)).containsExactlyElementsOf(expected);
            assertThat(rows).hasSize(11).allSatisfy(r -> assertThat(r.referrals()).isNotNull());
            assertThat(x.count(query)).isEqualTo(11L);
            assertThat(x.page(query, PageSpec.ofOffset(7, 7), CountMode.COUNT).total()).hasValue(11L);
            List<Row> exported = new ArrayList<>();
            x.export(query, ExportOptions.of(4), p -> p, exported::add);
            assertThat(exported).containsExactlyElementsOf(rows);
        }));
    }

    // ---- AC-SRC-66: cell 3, a two-join chain, the anchor on a to-one path below the second

    @TckTest
    void ac_src_66_a_source_on_a_to_one_path_below_a_two_join_chain_matches_native_sql(TckDatabase db)
            throws SQLException {
        var id = ColumnField.of(Contact.class, ORDERS, "id", Long.class).named("id");
        var referred = referred(Contact.class, JoinKind.LEFT);
        var twin = EntityJoin.of("twin", OrderEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(ColumnField.of(Contact.class, referred.root(), "id", Long.class), "id")));
        var twinCustomer = TableField.join(twin.root(), "customer", JoinType.LEFT);
        var contacts = SourceJoin.of("contacts", Contacts.class, JoinKind.LEFT, List.of(SourceJoin.on(
                ColumnField.of(Contact.class, twinCustomer, "email", String.class), S_EMAIL)));
        var country = ColumnField.over(Contact.class, contacts.root(), S_COUNTRY).named("country");
        var query = ModelQuery.builder(ORDERS, row -> new Contact(row.get(id), row.get(country)))
                .joinSource(contacts, CONTACTS).joinEntity(referred).joinEntity(twin)
                .select(SelectSet.of(id, country)).primaryKey(PrimaryKey.of(id))
                .where(f -> f.gte(id, 990L).lte(id, 1030L)).orderBy(id.asc()).build();
        List<Contact> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            rows.addAll(x.list(query, Limit.unlimited()));
            assertThat(x.count(query)).isEqualTo(41L);
        }));
        assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.country())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, k.country from orders o
                        left join customers r on r.id = o.referrer_id
                        left join orders t on t.id = r.id
                        left join customers tc on tc.id = t.customer_id
                        left join (select email, country from customers where country in ('DE', 'FR', 'JP')) k
                            on k.email = tc.email
                        where o.id between 990 and 1030 order by o.id"""));
        assertThat(rows).hasSize(41).anySatisfy(r -> assertThat(r.country()).isNotNull());
        assertThat(sql).first().satisfies(statement -> assertThat(joined(statement)).as(statement)
                .containsExactly("customers", "orders", "customers", "source"));
    }

    // ---- AC-SRC-66: cell 4, an entity join anchored on the source that reads an entity join

    @TckTest
    void ac_src_66_an_entity_join_on_the_source_follows_it(TckDatabase db) throws SQLException {
        var id = ColumnField.of(Top.class, ORDERS, "id", Long.class).named("id");
        var referred = referred(Top.class, JoinKind.LEFT);
        var source = referrals(Top.class, referred, JoinKind.LEFT);
        var count = ColumnField.over(Top.class, source.root(), S_COUNT).named("referrals");
        var top = EntityJoin.of("top", CustomerEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(ColumnField.over(Top.class, source.root(), S_REFERRER), "id")));
        var name = ColumnField.of(Top.class, top.root(), "name", String.class).named("top");
        var query = ModelQuery.builder(ORDERS, row -> new Top(row.get(id), row.get(count), row.get(name)))
                .joinEntity(top).joinSource(source, REFERRALS).joinEntity(referred)
                .select(SelectSet.of(id, count, name)).primaryKey(PrimaryKey.of(id))
                .where(f -> f.gte(id, 990L).lte(id, 1030L)).orderBy(id.asc()).build();
        List<Top> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            rows.addAll(x.list(query, Limit.unlimited()));
            assertThat(x.count(query)).isEqualTo(41L);
        }));
        assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.referrals(), r.top())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, k.n, c.name from orders o
                        left join customers r on r.id = o.referrer_id
                        left join %s k on k.referrer_id = r.id
                        left join customers c on c.id = k.referrer_id
                        where o.id between 990 and 1030 order by o.id""".formatted(NATIVE_REFERRALS)));
        assertThat(rows).filteredOn(r -> r.top() != null).hasSize(11);
        assertThat(sql).allSatisfy(statement -> assertThat(joined(statement)).as(statement)
                .containsExactly("customers", "source", "customers"));
    }

    // ---- AC-SRC-66: cell 5, a tree source anchored on an entity join

    @TckTest
    void ac_src_66_a_tree_source_on_an_entity_join_walks_once_in_its_on(TckDatabase db) throws SQLException {
        var folders = TableField.root(FolderNodeEntity.class);
        var id = ColumnField.of(Leveled.class, folders, "id", Long.class).named("id");
        var parent = EntityJoin.of("parent", FolderNodeEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(ColumnField.of(Leveled.class, folders, "parentId", Long.class), "id")));
        var nodes = SourceJoin.of("nodes", FolderNode.class, JoinKind.LEFT,
                List.of(SourceJoin.on(ColumnField.of(Leveled.class, parent.root(), "id", Long.class), QFolderNode.ID)));
        var level = ColumnField.over(Leveled.class, nodes.root(), QFolderNode.LEVEL).named("parentLevel");
        var walk = QFolderNode.source().where(f -> f.in(QFolderNode.ID, List.of(1L)))
                .tree(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), 3).build();
        var query = ModelQuery.builder(folders, row -> new Leveled(row.get(id), row.get(level)))
                .joinSource(nodes, walk).joinEntity(parent).select(SelectSet.of(id, level))
                .primaryKey(PrimaryKey.of(id)).where(f -> f.lte(id, 15L)).orderBy(id.asc()).build();
        List<Leveled> rows = new ArrayList<>();
        long[] counted = new long[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> run(ds, FolderNodeEntity.class, x -> {
            rows.addAll(x.list(query, Limit.unlimited()));
            counted[0] = x.count(query);
        }));
        // The walk from node 1 (depth 3) levels the tree 1 to 6; every other node has no levelled parent.
        Map<Long, Integer> levels = Map.of(1L, 0, 2L, 1, 3L, 1, 4L, 2, 5L, 2, 6L, 2);
        Map<Long, Long> parents = new HashMap<>();
        for (String row : SourceJoinExecutionTest.rows(db, "select id, parent_id from folder_nodes where id <= 15")) {
            String[] values = row.split("\\|");
            parents.put(Long.valueOf(values[0]), "null".equals(values[1]) ? null : Long.valueOf(values[1]));
        }
        assertThat(counted[0]).isEqualTo(parents.size());
        assertThat(rows).hasSize(parents.size()).allSatisfy(r ->
                assertThat(r.parentLevel()).as("node %s", r.id())
                        .isEqualTo(parents.get(r.id()) == null ? null : levels.get(parents.get(r.id()))));
        assertThat(rows).filteredOn(r -> r.parentLevel() != null).extracting(Leveled::id)
                .containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(sql).hasSize(2).allSatisfy(statement -> {
            String lower = statement.toLowerCase(Locale.ROOT);
            assertThat(lower).as(statement).startsWith("with recursive")
                    .containsPattern(" on \\w+\\.mq_id ?= ?\\w+\\.id and \\w+\\.mq_level ?<= ?\\?");
            assertThat(joined(statement)).as(statement).containsExactly("folder_nodes", "source");
        });
    }

    // ---- AC-SRC-66: cell 6, grouped by a column of the source

    @TckTest
    void ac_src_66_grouped_by_a_source_column_counts_groups_client_side(TckDatabase db) throws SQLException {
        for (var kind : List.of(JoinKind.LEFT, JoinKind.INNER)) {
            var referred = referred(Group.class, kind);
            var source = referrals(Group.class, referred, kind);
            var referrals = ColumnField.over(Group.class, source.root(), S_COUNT).named("referrals");
            var counted = Agg.<Group>count(source.root()).named("counted");
            var id = ColumnField.of(Group.class, ORDERS, "id", Long.class);
            var query = ModelQuery.builder(ORDERS, row -> new Group(row.get(referrals), row.get(counted)))
                    .joinSource(source, REFERRALS).joinEntity(referred).select(SelectSet.of(referrals, counted))
                    .groupBy(referrals).where(f -> f.gte(id, 990L).lte(id, 1030L)).build();
            List<Group> rows = new ArrayList<>();
            long[] groups = new long[1];
            List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
                rows.addAll(x.list(query, Limit.unlimited()));
                groups[0] = x.count(query);
            }));
            String join = kind == JoinKind.INNER ? "inner" : "left";
            List<String> expected = SourceJoinExecutionTest.rows(db, """
                    select k.n, count(k.referrer_id) from orders o
                    %s join customers r on r.id = o.referrer_id
                    %s join %s k on k.referrer_id = r.id
                    where o.id between 990 and 1030 group by k.n""".formatted(join, join, NATIVE_REFERRALS));
            assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.referrals(), r.counted())))
                    .as(kind.name()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(groups[0]).as(kind.name()).isEqualTo(expected.size());
            // The source's own count(*) is in its body; a LEFT join's count counts the source's key (R-AGG-19).
            assertThat(sql).allSatisfy(statement -> assertThat(statement).doesNotContain("count(("));
            if (kind == JoinKind.LEFT) {
                assertThat(sql).first().satisfies(statement -> assertThat(statement.toLowerCase(Locale.ROOT))
                        .containsPattern("count\\(\\w+\\.mq_referrerid\\)"));
                assertThat(sql).allSatisfy(statement ->
                        assertThat(SourceJoinExecutionTest.occurrences(statement, "count(*)")).isOne());
            }
        }
    }

    // ---- AC-SRC-66: cell 8, a joinFilter on the entity join

    @TckTest
    void ac_src_66_a_left_source_misses_where_the_join_filter_blanks_its_anchor(TckDatabase db) throws SQLException {
        var country = ColumnField.of(Row.class, REFERRED.root(), "country", String.class);
        var query = rows().joinFilter(REFERRED, f -> f.eq(country, "DE")).build();
        List<Row> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            rows.addAll(x.list(query, Limit.unlimited()));
            assertThat(x.count(query)).isEqualTo(41L);
        }));
        assertThat(text(rows)).containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                select o.id, r.name, k.n from orders o
                left join customers r on r.id = o.referrer_id and r.country = 'DE'
                left join %s k on k.referrer_id = r.id
                where o.id between 990 and 1030 order by o.id""".formatted(NATIVE_REFERRALS)));
        assertThat(rows).hasSize(41);
        // Of the eleven referrers the source keeps, only customer 254 is in DE.
        assertThat(rows).filteredOn(r -> r.referrals() != null).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(1023L));
    }

    // ---- AC-SRC-68: cell 8, an INNER source through a joinFilter join, entity-anchored or root-anchored

    @TckTest
    void ac_src_68_an_inner_source_reading_through_a_join_filter_is_mq1912_before_any_statement(TckDatabase db) {
        // A joinFilter binds only a LEFT join, and an INNER source on a LEFT entity join is MQ1910 at build, so the
        // entity-anchored case reads a filtered LEFT path below an INNER join.
        var twin = EntityJoin.of("twin", OrderEntity.class, JoinKind.INNER, List.of(EntityJoin.on(ID, "id")));
        var twinReferrer = TableField.join(twin.root(), "referrer", JoinType.LEFT);
        var twinCountry = ColumnField.of(Row.class, twinReferrer, "country", String.class);
        var onTwin = SourceJoin.of("referrals", Referrals.class, JoinKind.INNER,
                List.of(SourceJoin.on(ColumnField.of(Row.class, twinReferrer, "id", Long.class), S_REFERRER)));
        var twinCount = ColumnField.over(Row.class, onTwin.root(), S_COUNT).named("referrals");
        var onEntity = ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), null, row.get(twinCount)))
                .joinSource(onTwin, REFERRALS).joinEntity(twin).select(SelectSet.of(ID, twinCount))
                .primaryKey(PrimaryKey.of(ID)).joinFilter(twinReferrer, f -> f.eq(twinCountry, "DE"))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc()).build();
        var referrer = TableField.join(ORDERS, "referrer", JoinType.LEFT);
        var referrerCountry = ColumnField.of(Row.class, referrer, "country", String.class);
        var onPath = SourceJoin.of("referrals", Referrals.class, JoinKind.INNER,
                List.of(SourceJoin.on(ColumnField.of(Row.class, referrer, "id", Long.class), S_REFERRER)));
        var count = ColumnField.over(Row.class, onPath.root(), S_COUNT).named("referrals");
        var onRoot = ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), null, row.get(count)))
                .joinSource(onPath, REFERRALS).select(SelectSet.of(ID, count)).primaryKey(PrimaryKey.of(ID))
                .joinFilter(referrer, f -> f.eq(referrerCountry, "DE"))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (var query : List.of(onEntity, onRoot)) {
                assertThatThrownBy(() -> x.list(query, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1912))
                        .hasMessageContaining("join 'referrals' of Referrals is INNER")
                        .hasMessageContaining("reads through the joinFilter(...)")
                        .hasMessageContaining("declare the source join LEFT");
                assertThatThrownBy(() -> x.count(query))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1912))
                        .hasMessageContaining("join 'referrals' of Referrals is INNER")
                        .hasMessageContaining("reads through the joinFilter(...)")
                        .hasMessageContaining("declare the source join LEFT");
            }
        }));
        assertThat(sql).isEmpty();
    }

    // ---- AC-SRC-67: an outer column through a to-many path repeats each outer row (D-219)

    @TckTest
    void ac_src_67_a_to_many_outer_column_is_refused_before_any_statement(TckDatabase db) {
        var items = TableField.join(ORDERS, "items", JoinType.LEFT);
        var onRoot = SourceJoin.of("referrals", Referrals.class, JoinKind.LEFT,
                List.of(SourceJoin.on(ColumnField.of(Row.class, items, "id", Long.class), S_REFERRER)));
        // Below an entity join, a to-many segment is already MQ1911 where the path resolves (D-218).
        var orders = TableField.join(REFERRED.root(), "orders", JoinType.LEFT);
        var onEntity = SourceJoin.of("referrals", Referrals.class, JoinKind.LEFT,
                List.of(SourceJoin.on(ColumnField.of(Row.class, orders, "id", Long.class), S_REFERRER)));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (var join : List.of(onRoot, onEntity)) {
                MqCode expected = join == onRoot ? MqCode.MQ1910 : MqCode.MQ1911;
                var count = ColumnField.over(Row.class, join.root(), S_COUNT).named("referrals");
                var query = ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), null, row.get(count)))
                        .joinSource(join, REFERRALS).joinEntity(REFERRED).select(SelectSet.of(ID, count))
                        .primaryKey(PrimaryKey.of(ID)).where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc())
                        .build();
                for (Runnable run : List.<Runnable>of(() -> x.list(query, Limit.unlimited()), () -> x.count(query))) {
                    assertThatThrownBy(run::run)
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).as(e.getMessage()).isEqualTo(expected))
                            .hasMessageContaining(join == onRoot ? "outer column id reads through the to-many items"
                                    : "a path below an entity join must be to-one");
                }
            }
        }));
        assertThat(sql).isEmpty();
    }

    // ---- AC-SRC-69: cell 7, a through child statement

    @TckTest
    void ac_src_69_a_through_child_attaches_the_source_to_the_statement_root(TckDatabase db) throws SQLException {
        var id = ColumnField.of(Parent.class, ORDERS, "id", Long.class).named("id");
        var through = TableField.join(TableField.join(ORDERS, "customer", JoinType.INNER), "orders", JoinType.INNER);
        ChildField<Parent, Row> children = new ChildField<>() {
            @Override
            public String name() {
                return "orders";
            }

            @Override
            public ColumnField<Parent, ?, ?> key() {
                return id;
            }

            @Override
            public Optional<ColumnField<Row, ?, ?>> foreignKey() {
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
            public ModelQuery.Builder<?, ?, Row> query() {
                return ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), row.get(NAME), row.get(COUNT)))
                        .joinSource(SOURCE, REFERRALS).joinEntity(REFERRED).primaryKey(PrimaryKey.of(ID))
                        .orderBy(ID.asc());
            }

            @Override
            public Parent with(Parent parent, List<Row> rows) {
                return new Parent(parent.id(), rows);
            }
        };
        var plan = FetchPlan.of(SelectSet.of(id)).child(children, FetchPlan.of(SelectSet.of(ID, NAME, COUNT)));
        var query = ModelQuery.builder(ORDERS, row -> new Parent(row.get(id), List.of()))
                .select(SelectSet.of(id)).primaryKey(PrimaryKey.of(id)).where(f -> f.in(id, List.of(990L, 991L)))
                .orderBy(id.asc()).fetch(plan).build();
        List<Parent> parents = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x ->
                parents.addAll(x.list(query, Limit.unlimited()))));
        List<String> expected = SourceJoinExecutionTest.rows(db, """
                select i.id, r.name, k.n from orders p
                join customers c on c.id = p.customer_id
                join orders i on i.customer_id = c.id
                left join customers r on r.id = i.referrer_id
                left join %s k on k.referrer_id = r.id
                where p.id in (990, 991) order by p.id, i.id""".formatted(NATIVE_REFERRALS));
        assertThat(parents).extracting(Parent::id).containsExactly(990L, 991L);
        assertThat(parents.stream().flatMap(p -> text(p.orders()).stream())).containsExactlyElementsOf(expected);
        assertThat(parents.get(0).orders()).anySatisfy(r -> assertThat(r.referrals()).isNotNull());
        assertThat(sql).hasSize(2).last().satisfies(statement ->
                assertThat(joined(statement)).as(statement).containsExactly("customers", "orders", "customers",
                        "source"));
    }

    // ---- helpers

    private static List<String> text(List<Row> rows) {
        return rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.referred(), r.referrals())).toList();
    }

    private static final Pattern JOIN = Pattern.compile("\\bjoin\\s+(\\S+)");

    private static final List<String> TABLES = List.of("customers", "orders", "folder_nodes", "labels");

    /**
     * The tables {@code statement} joins in order, outside every parenthesised part (a CTE or derived table body, an
     * ON's grouping): an entity's table by name, a CTE or an inlined derived table as {@code source}.
     */
    static List<String> joined(String statement) {
        StringBuilder outer = new StringBuilder();
        int depth = 0;
        for (char c : statement.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '(' && depth++ == 0) {
                outer.append("(sub)");
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                outer.append(c);
            }
        }
        List<String> tables = new ArrayList<>();
        Matcher matcher = JOIN.matcher(outer);
        while (matcher.find()) {
            tables.add(TABLES.contains(matcher.group(1)) ? matcher.group(1) : "source");
        }
        return tables;
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        run(ds, OrderEntity.class, work);
    }

    private static <E> void run(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
