package com.rey.modelquery.hibernate;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.SubqueryRenderer;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.Attribute;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real Criteria rendering, without JPA doubles, of the ordered scalar sub-query (AC-COL-40). */
class CorrelatedFirstRenderingTest {

    @Entity(name = "FirstParent")
    static class Parent {
        @Id Long id;
    }

    @Entity(name = "FirstChild")
    static class Child {
        @Id Long id;
        Long parentId;
        Integer score;
        String label;
        @OneToMany @JoinColumn(name = "child_id") List<Part> parts;
    }

    @Entity(name = "FirstPart")
    static class Part {
        @Id Long id;
        String label;
    }

    @Embeddable
    static class Key implements Serializable {
        Long zeta;
        Long alpha;

        public Long getZeta() {
            return zeta;
        }

        public void setZeta(Long zeta) {
            this.zeta = zeta;
        }

        public Long getAlpha() {
            return alpha;
        }

        public void setAlpha(Long alpha) {
            this.alpha = alpha;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && java.util.Objects.equals(zeta, other.zeta)
                    && java.util.Objects.equals(alpha, other.alpha);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(zeta, alpha);
        }
    }

    @Entity(name = "FirstEmbedded")
    static class EmbeddedChild {
        @EmbeddedId Key id;
        Long parentId;
        String label;
    }

    @Entity(name = "FirstComposite")
    @IdClass(Key.class)
    static class CompositeChild {
        @Id Long zeta;
        @Id Long alpha;
        Long parentId;
        String label;
    }

    @Entity(name = "FirstPropertyComposite")
    @IdClass(Key.class)
    static class PropertyCompositeChild {
        private Long zeta;
        private Long alpha;
        private Long parentId;
        private String label;

        @Id
        public Long getZeta() {
            return zeta;
        }

        public void setZeta(Long zeta) {
            this.zeta = zeta;
        }

        @Id
        public Long getAlpha() {
            return alpha;
        }

        public void setAlpha(Long alpha) {
            this.alpha = alpha;
        }

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(Long parentId) {
            this.parentId = parentId;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    record View(String label) {}

    static final class Inner {}

    private static final TableField<Parent, Parent> ROOT = TableField.root(Parent.class);
    private static final TableField<Child, Child> CHILD = TableField.root(Child.class);
    private static final TableField<Child, Part> PARTS = TableField.join(CHILD, "parts", LEFT);
    private static final ColumnField<View, Parent, Long> ID = ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final ColumnField<Inner, Child, Long> PARENT_ID =
            ColumnField.of(Inner.class, CHILD, "parentId", Long.class);
    private static final ColumnField<Inner, Child, Integer> SCORE =
            ColumnField.of(Inner.class, CHILD, "score", Integer.class);
    private static final ColumnField<Inner, Child, String> LABEL =
            ColumnField.of(Inner.class, CHILD, "label", String.class);
    private static final ColumnField<Inner, Part, String> PART_LABEL =
            ColumnField.of(Inner.class, PARTS, "label", String.class);
    private static final List<String> SQL = new ArrayList<>();
    private static SessionFactory factory;

    @BeforeAll
    static void start() {
        factory = new Configuration().addAnnotatedClass(Parent.class).addAnnotatedClass(Child.class)
                .addAnnotatedClass(Part.class).addAnnotatedClass(EmbeddedChild.class)
                .addAnnotatedClass(CompositeChild.class).addAnnotatedClass(PropertyCompositeChild.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:correlated-first")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .setStatementInspector((StatementInspector) sql -> {
                    SQL.add(sql);
                    return sql;
                }).buildSessionFactory();
        factory.inTransaction(em -> {
            for (long id : new long[] {1, 2}) {
                var parent = new Parent();
                parent.id = id;
                em.persist(parent);
            }
            for (long id : new long[] {20, 10, 30}) {
                var child = new Child();
                child.id = id;
                child.parentId = 1L;
                child.score = id == 30 ? null : 5;
                child.label = id == 10 ? "low-id" : id == 20 ? "high-id" : "undated";
                em.persist(child);
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
    void ac_col_40_executor_wires_first_and_explicit_null_precedence_and_empty_rows() {
        factory.inSession(em -> {
            var executor = ModelQueryExecutor.create(em, Parent.class, ModelQueryConfig.defaults());
            for (boolean nullsFirst : new boolean[] {true, false}) {
                var first = first(nullsFirst ? SCORE.asc().nullsFirst() : SCORE.asc().nullsLast());
                var query = selecting(first).orderBy(ID.asc()).build();
                assertThat(executor.list(query, Limit.unlimited())).extracting(View::label)
                        .containsExactly(nullsFirst ? "undated" : "low-id", null);
            }
        });
    }

    @Test
    void ac_col_40_default_has_no_null_clause_and_equal_values_share_one_node_per_statement() {
        var first = first(SCORE.asc());
        var query = selecting(first).where(f -> f.eq(first(SCORE.asc()), "undated"))
                .orderBy(first(SCORE.asc()).asc()).build();
        var calls = new AtomicInteger();
        RenderOptions options = options().withSubqueryRenderer(new SubqueryRenderer() {
            @Override
            public <C> Expression<C> firstRow(CriteriaBuilder cb, Subquery<C> sub, List<Order> order) {
                calls.incrementAndGet();
                assertThat(order).hasSize(2);
                assertThat(order).allMatch(Order::isAscending);
                assertThat(order).extracting(CorrelatedFirstRenderingTest::attribute)
                        .containsExactly("score", "id");
                return new HibernateProviderSupport().subqueries().orElseThrow().firstRow(cb, sub, order);
            }
        });
        factory.inSession(em -> {
            for (int i = 0; i < 2; i++) {
                SQL.clear();
                var built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options);
                em.createQuery(built.query()).getResultList();
                assertThat(SQL).singleElement().asString().contains("fetch first ? rows only")
                        .doesNotContain("nulls first", "nulls last", "case when");
                // One node for the select, the where and the order, plus one in the throwaway scratch that tells
                // whether the where makes a to-many join (R-EXE-16): it renders with a context of its own.
                assertThat(calls).hasValue(2 * (i + 1));
            }
        });
    }

    @Test
    void ac_col_40_first_limit_binds_are_counted_for_single_and_repeated_uses() {
        var first = first(SCORE.asc());
        var queries = List.of(selecting(first).build(),
                selecting(first).where(f -> f.eq(first, "undated")).orderBy(first.asc()).build());
        int[] expectedBinds = {1, 4};
        // A direct order on the selected expression uses its SELECT position, without repeating the SQL sub-query.
        int[] expectedSqlBinds = {1, 3};
        factory.inSession(em -> {
            for (int i = 0; i < queries.size(); i++) {
                SQL.clear();
                var built = queries.get(i).buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options());
                var statement = em.createQuery(built.query());
                int binds = statement.getParameters().size() + built.joins().repeatedExpressionBinds();
                assertThat(binds).isEqualTo(expectedBinds[i]);
                statement.getResultList();
                assertThat(SQL).hasSize(1);
                assertThat(SQL.get(0).chars().filter(c -> c == '?').count()).isEqualTo(expectedSqlBinds[i]);
            }
        });
    }

    @Test
    void ac_col_40_query_compliance_refuses_first_with_mq4014_before_any_statement() {
        try (var strict = new Configuration().addAnnotatedClass(Parent.class).addAnnotatedClass(Child.class)
                .addAnnotatedClass(Part.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:correlated-first")
                .setProperty("hibernate.jpa.compliance.query", "true")
                .setStatementInspector((StatementInspector) sql -> {
                    SQL.add(sql);
                    return sql;
                }).buildSessionFactory()) {
            SQL.clear();
            strict.inSession(em -> {
                var executor = ModelQueryExecutor.create(em, Parent.class, ModelQueryConfig.defaults());
                var query = selecting(first(SCORE.asc())).build();
                assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                        .hasMessageContaining("hibernate.jpa.compliance.query=false")
                        .hasCauseInstanceOf(IllegalStateException.class);
            });
            assertThat(SQL).isEmpty();
        }
    }

    @Test
    void ac_col_40_inner_orders_use_the_portable_null_precedence_fallback() {
        factory.inSession(em -> {
            var first = first(SCORE.asc().nullsLast());
            var built = selecting(first).orderBy(ID.asc()).build().buildQuery(em.getCriteriaBuilder(), Phase.MODEL,
                    RenderOptions.portable().withSubqueryRenderer(new HibernateProviderSupport()
                            .subqueries().orElseThrow()));
            SQL.clear();
            assertThat(em.createQuery(built.query()).getResultList()).extracting(row -> row.get(0))
                    .containsExactly("low-id", null);
            assertThat(SQL).singleElement().asString().contains("case when");
        });
    }

    @Test
    void ac_col_40_first_refuses_fanout_in_selection_where_correlation_and_order() {
        var values = List.of(
                Correlated.first(View.class, SubSelect.of(PART_LABEL),
                        (f, outer) -> f.compare(PARENT_ID, Op.EQ, outer.column(ID)), SCORE.asc()),
                Correlated.first(View.class, SubSelect.of(LABEL).where(f -> f.eq(PART_LABEL, "x")),
                        (f, outer) -> f.compare(PARENT_ID, Op.EQ, outer.column(ID)), SCORE.asc()),
                Correlated.first(View.class, SubSelect.of(LABEL),
                        (f, outer) -> f.compare(PARENT_ID, Op.EQ, outer.column(ID)).eq(PART_LABEL, "x"), SCORE.asc()),
                first(PART_LABEL.asc()));
        factory.inSession(em -> {
            SQL.clear();
            for (var value : values) {
                assertThatThrownBy(() -> selecting(value).build()
                        .buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1511))
                        .hasMessageContaining("Filters.exists(path, inner)");
            }
            assertThat(SQL).isEmpty();
        });
    }

    @Test
    void ac_col_40_embedded_and_field_and_property_id_class_tie_breakers_follow_attribute_name_order() {
        for (Class<?> type : List.of(EmbeddedChild.class, CompositeChild.class, PropertyCompositeChild.class)) {
            var root = TableField.root(type);
            var label = ColumnField.of(Inner.class, root, "label", String.class);
            var parent = ColumnField.of(Inner.class, root, "parentId", Long.class);
            var first = Correlated.first(View.class, SubSelect.of(label),
                    (f, outer) -> f.compare(parent, Op.EQ, outer.column(ID)), label.asc());
            var names = new ArrayList<String>();
            RenderOptions options = options().withSubqueryRenderer(new SubqueryRenderer() {
                @Override
                public <C> Expression<C> firstRow(CriteriaBuilder cb, Subquery<C> sub, List<Order> order) {
                    order.forEach(o -> names.add(attribute(o)));
                    assertThat(order).allMatch(Order::isAscending);
                    return new HibernateProviderSupport().subqueries().orElseThrow().firstRow(cb, sub, order);
                }
            });
            selecting(first).build().buildQuery(factory.getCriteriaBuilder(), Phase.MODEL, options);
            assertThat(names).containsExactly("label", "alpha", "zeta");
        }
    }

    @Test
    void ac_col_40_phase_checks_and_option_copies_keep_the_subquery_renderer() {
        var provider = new HibernateProviderSupport();
        var renderer = provider.subqueries().orElseThrow();
        var options = RenderOptions.of(123, 456).withSubqueryRenderer(renderer)
                .withSourceRenderer(provider.sources().orElseThrow())
                .withNullPrecedenceRenderer(provider.nullPrecedence().orElseThrow())
                .withConstantRenderer((entity, attribute, value) -> java.util.Optional.of(value))
                .withSequenceRenderer((cb, sequence) -> cb.nullLiteral(Long.class));
        assertThat(options.subqueryRenderer()).containsSame(renderer);
        assertThat(options.maxInListSize()).isEqualTo(123);
        assertThat(options.maxBindParameters()).isEqualTo(456);
        assertThat(RenderOptions.portable().subqueryRenderer()).isEmpty();
        var query = selecting(first(SCORE.asc())).customize((spec, joins, criteria, cb, phase) -> {}).build();
        query.checkPhases(factory.getCriteriaBuilder(), options);
    }

    private static ExpressionField<View, String> first(OrderField<Inner, ?> order) {
        return Correlated.first(View.class, SubSelect.of(LABEL),
                (f, outer) -> f.compare(PARENT_ID, Op.EQ, outer.column(ID)), order);
    }

    private static ModelQuery.Builder<Parent, Object, View> selecting(ExpressionField<View, String> first) {
        return ModelQuery.builder(ROOT, row -> new View(row.get(first))).select(SelectSet.of(first));
    }

    private static RenderOptions options() {
        var provider = new HibernateProviderSupport();
        return RenderOptions.portable().withNullPrecedenceRenderer(provider.nullPrecedence().orElseThrow())
                .withSubqueryRenderer(provider.subqueries().orElseThrow());
    }

    private static String attribute(Order order) {
        return ((Attribute<?, ?>) ((Path<?>) order.getExpression()).getModel()).getName();
    }
}
