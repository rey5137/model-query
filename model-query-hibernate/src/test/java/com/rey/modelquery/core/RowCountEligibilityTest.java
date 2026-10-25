package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * R-AGG-19's eligibility, read from the resolved join chain of a real Criteria statement: a criteria root or an INNER
 * chain from one, untreated, counting its own id, in a statement without a customizer (D-185). The TCK checks the SQL
 * and rows of every site on every vendor (AC-AGG-27 to AC-AGG-29).
 */
class RowCountEligibilityTest {

    @Entity(name = "RcRegion")
    static class Region {
        @Id
        Long id;
    }

    @Entity(name = "RcCustomer")
    static class Customer {
        @Id
        Long id;
        String name;
        @ManyToOne
        Region region;
    }

    @Entity(name = "RcPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class Payment {
        @Id
        Long id;
    }

    @Entity(name = "RcCard")
    static class Card extends Payment {
        String number;
        @ManyToOne
        Customer issuer;
    }

    @Entity(name = "RcOrder")
    static class Order {
        @Id
        Long id;
        String name;
        @ManyToOne
        Customer customer;
        @ManyToOne
        Payment payment;
    }

    /** The composite key of {@link Line}. */
    static class LineKey implements Serializable {
        Long orderId;
        Integer lineNo;

        @Override
        public boolean equals(Object o) {
            return o instanceof LineKey k && Objects.equals(orderId, k.orderId) && Objects.equals(lineNo, k.lineNo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, lineNo);
        }
    }

    @Entity(name = "RcLine")
    @IdClass(LineKey.class)
    static class Line {
        @Id
        Long orderId;
        @Id
        Integer lineNo;
    }

    record Row(Long n) {}

    /** Reads a node as its subtype with standard Criteria, so a treated node is made without the provider's SPI. */
    static final class StandardTreats implements TreatRenderer {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
            return from instanceof Root<?> root ? cb.treat((Root) root, subtype) : cb.treat((Join) from, subtype);
        }

        @Override
        public Optional<String> refusal(TreatSite site) {
            return Optional.empty();
        }

        @Override
        public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape,
                List<ExpectedNarrowing> expected) {
            return Optional.empty();
        }
    }

    /** Renders the row count as {@code count(*)}, as the Hibernate renderer does. */
    static final class Rows implements AggregateRenderer {
        @Override
        public Expression<Long> rowCount(CriteriaBuilder cb, Expression<?> current) {
            return ((org.hibernate.query.criteria.HibernateCriteriaBuilder) cb).count();
        }
    }

    private static final List<String> STATEMENTS = new ArrayList<>();

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Customer> INNER_CUSTOMER =
            TableField.join(ROOT, "customer", JoinType.INNER);
    private static final TableField<Order, Customer> LEFT_CUSTOMER = TableField.join(ROOT, "customer", JoinType.LEFT);
    private static final TableField<Order, Payment> INNER_PAYMENT = TableField.join(ROOT, "payment", JoinType.INNER);
    private static final ColumnField<Row, Order, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, Order, String> NAME = ColumnField.of(Row.class, ROOT, "name", String.class);
    private static final ColumnField<Row, Order, Long> CUSTOMER_FK =
            ColumnField.of(Row.class, ROOT, "customer", Long.class);
    private static final ColumnField<Row, Customer, Long> INNER_CUSTOMER_ID =
            ColumnField.of(Row.class, INNER_CUSTOMER, "id", Long.class);
    private static final ColumnField<Row, Customer, Long> LEFT_CUSTOMER_ID =
            ColumnField.of(Row.class, LEFT_CUSTOMER, "id", Long.class);

    private static SessionFactory factory;

    private CriteriaBuilder cb;
    private CriteriaQuery<Object> query;
    private JoinContext ctx;

    @BeforeAll
    static void start() {
        Configuration cfg = new Configuration()
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:row-count-eligibility")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        cfg.getProperties().put(AvailableSettings.STATEMENT_INSPECTOR, (StatementInspector) sql -> {
            STATEMENTS.add(sql);
            return sql;
        });
        for (Class<?> entity : List.of(Region.class, Customer.class, Payment.class, Card.class, Order.class,
                Line.class)) {
            cfg.addAnnotatedClass(entity);
        }
        factory = cfg.buildSessionFactory();
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    @BeforeEach
    void statement() {
        cb = factory.getCriteriaBuilder();
        query = cb.createQuery();
        ctx = JoinContext.readOf(query.from(Order.class), cb, query,
                RenderOptions.portable().withTreatRenderer(new StandardTreats()));
    }

    @Test
    void ac_agg_27_the_root_and_its_id_column_count_rows() {
        assertThat(RowCounts.eligible(ctx, ROOT.resolve(ctx))).isTrue();
        assertThat(RowCounts.eligible(ctx, ID.expression(ctx))).isTrue();
    }

    @Test
    void ac_agg_27_an_inner_chain_and_its_id_column_count_rows() {
        assertThat(RowCounts.eligible(ctx, INNER_CUSTOMER.resolve(ctx))).isTrue();
        assertThat(RowCounts.eligible(ctx, INNER_CUSTOMER_ID.expression(ctx))).isTrue();
        assertThat(RowCounts.eligible(ctx, TableField.join(INNER_CUSTOMER, "region", JoinType.INNER).resolve(ctx)))
                .isTrue();
    }

    @Test
    void ac_agg_28_a_left_hop_anywhere_on_the_chain_keeps_the_id() {
        assertThat(RowCounts.eligible(ctx, LEFT_CUSTOMER.resolve(ctx))).isFalse();
        assertThat(RowCounts.eligible(ctx, LEFT_CUSTOMER_ID.expression(ctx))).isFalse();
        assertThat(RowCounts.eligible(ctx, TableField.join(LEFT_CUSTOMER, "region", JoinType.INNER).resolve(ctx)))
                .isFalse();
    }

    @Test
    void ac_agg_28_a_treated_node_on_the_chain_keeps_the_id() {
        var card = INNER_PAYMENT.treat(Card.class);
        assertThat(RowCounts.eligible(ctx, INNER_PAYMENT.resolve(ctx))).isTrue();
        assertThat(RowCounts.eligible(ctx, card.resolve(ctx))).isFalse();
        assertThat(RowCounts.eligible(ctx, TableField.join(card, "issuer", JoinType.INNER).resolve(ctx))).isFalse();
    }

    @Test
    void ac_agg_28_a_rooted_at_context_walks_its_root_join_not_its_root() {
        From<?, ?> left = LEFT_CUSTOMER.resolve(ctx);
        JoinContext rooted = ctx.rootedAt(left, Customer.class);
        assertThat(RowCounts.eligible(rooted, rooted.root())).isFalse();
        From<?, ?> inner = INNER_CUSTOMER.resolve(ctx);
        JoinContext through = ctx.rootedAt(inner, Customer.class);
        assertThat(RowCounts.eligible(through, through.root())).isTrue();
        From<?, ?> treated = INNER_PAYMENT.treat(Card.class).resolve(ctx);
        JoinContext throughTreat = ctx.rootedAt(treated, Card.class);
        assertThat(RowCounts.eligible(throughTreat, throughTreat.root())).isFalse();
    }

    @Test
    void ac_agg_28_a_statement_with_a_customizer_keeps_the_id() {
        ctx.customized(true);
        assertThat(RowCounts.eligible(ctx, ROOT.resolve(ctx))).isFalse();
        assertThat(RowCounts.eligible(ctx, ID.expression(ctx))).isFalse();
    }

    @Test
    void ac_agg_28_a_foreign_key_read_and_a_non_id_column_keep_their_count() {
        assertThat(RowCounts.eligible(ctx, CUSTOMER_FK.expression(ctx))).isFalse();
        JoinContext write = JoinContext.of(query.from(Order.class), cb, query, RenderOptions.portable());
        assertThat(RowCounts.eligible(write, CUSTOMER_FK.expression(write))).isFalse();
        assertThat(RowCounts.eligible(ctx, NAME.expression(ctx))).isFalse();
        assertThat(RowCounts.eligible(ctx, cb.literal(1))).isFalse();
    }

    @Test
    void ac_agg_29_a_joined_source_counts_rows_only_through_an_inner_join_from_an_eligible_outer() {
        // The join node stands in for a CTE join: a joined source is classified by its recorded kind and outer From,
        // never by the node's type (HibernateSourceJoinTest runs the real CTE joins).
        From<?, ?> stand = ctx.root().join("customer", JoinType.LEFT);
        // An INNER join's id, which the entity rule alone would count as rows: the LEFT kind keeps the column.
        From<?, ?> innerStand = ctx.root().join("payment", JoinType.INNER);
        JoinKey inner = JoinKey.joinedSource(Customer.class, "inner");
        JoinKey left = JoinKey.joinedSource(Customer.class, "left");
        JoinKey overLeft = JoinKey.joinedSource(Customer.class, "overLeft");
        ctx.joinSource(inner, stand, ctx.root(), true, "name");
        ctx.joinSource(left, innerStand, ctx.root(), false, "id");
        ctx.joinSource(overLeft, stand, LEFT_CUSTOMER.resolve(ctx), true, "region");

        assertThat(RowCounts.eligible(ctx, ctx.joinedSourceKey(inner, "count"))).isTrue();
        assertThat(RowCounts.eligible(ctx, ctx.joinedSourceKey(left, "count"))).isFalse();
        assertThat(RowCounts.eligible(ctx, ctx.joinedSourceKey(overLeft, "count"))).isFalse();
        ctx.customized(true);
        assertThat(RowCounts.eligible(ctx, ctx.joinedSourceKey(inner, "count"))).isFalse();
    }

    @Test
    void ac_agg_31_an_entity_join_and_its_descendants_follow_the_recorded_kind_and_attached_root() {
        var renderer = new HibernateProviderSupport().entityJoins().orElseThrow();
        for (JoinKind kind : List.of(JoinKind.INNER, JoinKind.LEFT)) {
            From<?, Customer> entity = renderer.join(ctx.root(), Customer.class, kind, node -> cb.conjunction());
            JoinKey key = JoinKey.joinedEntity(Customer.class, kind.name());
            ctx.joinEntity(key, entity, ctx.root(), kind == JoinKind.INNER, entity.get("id"));
            From<?, ?> descendant = entity.join("region", JoinType.INNER);
            boolean inner = kind == JoinKind.INNER;
            assertThat(RowCounts.eligible(ctx, entity)).isEqualTo(inner);
            assertThat(RowCounts.eligible(ctx, entity.get("id"))).isEqualTo(inner);
            assertThat(RowCounts.eligible(ctx, descendant)).isEqualTo(inner);
            assertThat(RowCounts.eligible(ctx, descendant.get("id"))).isEqualTo(inner);
            if (inner) {
                ctx.customized(true);
                assertThat(RowCounts.eligible(ctx, entity)).isFalse();
                assertThat(RowCounts.eligible(ctx, descendant)).isFalse();
                ctx.customized(false);
                ctx.joinEntity(key, entity, LEFT_CUSTOMER.resolve(ctx), true, entity.get("id"));
                assertThat(RowCounts.eligible(ctx, entity)).isFalse();
                assertThat(RowCounts.eligible(ctx, descendant)).isFalse();
            }
        }
    }

    @Test
    void ac_agg_32_filtered_counts_over_empty_roots_and_entity_joins_are_zero_with_either_renderer() {
        var provider = new HibernateProviderSupport();
        for (AggregateRenderer renderer : List.of(AggregateRenderer.DEFAULT, provider.aggregates().orElseThrow())) {
            var options = RenderOptions.portable().withAggregateRenderer(renderer);
            var rootCount = Agg.<Row>count(ROOT).filter(f -> f.gt(ID, 0L));
            assertThat(sql(Order.class, options, (c, q) -> rootCount.expression(q))).contains("coalesce(");
            var copy = EntityJoin.of("copy", Order.class, JoinKind.INNER, List.of(EntityJoin.on(ID, "id")));
            var count = Agg.<Row>count(copy.root()).filter(f -> f.gt(ID, 0L));
            assertThat(sql(Order.class, options, (c, q) -> {
                From<?, Order> entity = provider.entityJoins().orElseThrow()
                        .join(q.root(), Order.class, JoinKind.INNER, node -> c.conjunction());
                q.joinEntity(copy.root().key(), entity, q.root(), true, entity.get("id"));
                return count.expression(q);
            })).contains("coalesce(");
        }
    }

    @Test
    void ac_agg_27_a_composite_id_table_counts_as_one_identity_and_its_id_parts_do_not() {
        CriteriaQuery<Object> lines = cb.createQuery();
        JoinContext lineCtx = JoinContext.readOf(lines.from(Line.class), cb, lines, RenderOptions.portable());
        TableField<Line, Line> line = TableField.root(Line.class);
        assertThat(RowCounts.eligible(lineCtx, line.resolve(lineCtx))).isTrue();
        assertThat(RowCounts.eligible(lineCtx,
                ColumnField.of(Row.class, line, "lineNo", Integer.class).expression(lineCtx))).isFalse();
    }

    @Test
    void ac_agg_29_the_renderer_counts_rows_only_where_eligible_and_its_default_is_todays_count() {
        RenderOptions rows = RenderOptions.portable().withAggregateRenderer(new Rows());
        assertThat(sql(Order.class, rows, (c, q) -> RowCounts.count(q, q.root()))).contains("count(*)");
        assertThat(sql(Order.class, rows, (c, q) -> RowCounts.count(q, LEFT_CUSTOMER.resolve(q))))
                .contains("count(c1_0.id)").doesNotContain("count(*)");
        assertThat(sql(Order.class, RenderOptions.portable(), (c, q) -> RowCounts.count(q, q.root())))
                .contains("count(o1_0.id)").doesNotContain("count(*)");
        // A composite id already renders count(*), with or without the renderer.
        assertThat(sql(Line.class, RenderOptions.portable(), (c, q) -> RowCounts.count(q, q.root())))
                .contains("count(*)");
    }

    private interface Selection {
        Expression<Long> select(CriteriaBuilder cb, JoinContext ctx);
    }

    /** The SQL of {@code select <selection> from <entity>}, run on H2. */
    private static String sql(Class<?> entity, RenderOptions options, Selection selection) {
        CriteriaBuilder builder = factory.getCriteriaBuilder();
        CriteriaQuery<Long> statement = builder.createQuery(Long.class);
        JoinContext joins = JoinContext.readOf(statement.from(entity), builder, statement, options);
        statement.select(selection.select(builder, joins));
        STATEMENTS.clear();
        try (EntityManager em = factory.createEntityManager()) {
            assertThat(em.createQuery(statement).getSingleResult()).isZero();
        }
        assertThat(STATEMENTS).hasSize(1);
        return STATEMENTS.get(0);
    }
}
