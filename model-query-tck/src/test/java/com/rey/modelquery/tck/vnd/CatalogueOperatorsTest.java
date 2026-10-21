package com.rey.modelquery.tck.vnd;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.hibernate.HibernateOperators;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.ConstantRowEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.flt.OperatorProviderSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateOperators}' {@code ilike} and {@code regexMatch} per Tier-1 database: the rows each returns where
 * the vendor and Hibernate both serve its feature, and each refused before any statement where either doesn't, the
 * vendor's check first (spec api/12 R-FLT-20, R-FLT-21, R-FLT-06, vendor/40 R-VND-23, D-171; TCK AC-FLT-21,
 * AC-FLT-24's {@code ilike} and {@code regexMatch} cases).
 */
class CatalogueOperatorsTest {

    record R(Long id, String label) {}

    record O(Long id) {}

    static final TableField<ConstantRowEntity, ConstantRowEntity> ROWS = TableField.root(ConstantRowEntity.class);
    static final ColumnField<R, ConstantRowEntity, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, ConstantRowEntity, String> LABEL =
            ColumnField.of(R.class, ROWS, "label", String.class);
    /** The label column again, through a converter, so it can be the right side of a field form. */
    static final ColumnField<R, ConstantRowEntity, String> LABEL_AGAIN =
            ColumnField.of(R.class, ROWS, "label", String.class, String.class, new Same());
    /** The label column through a converter that stores a {@code p} before the model value. */
    static final ColumnField<R, ConstantRowEntity, String> PREFIXED =
            ColumnField.of(R.class, ROWS, "label", String.class, String.class, new Prefixed());
    static final ModelQuery.Builder<ConstantRowEntity, Object, R> ROWS_QUERY =
            ModelQuery.builder(ROWS, row -> new R(row.get(ID), row.get(LABEL)))
                    .select(SelectSet.of(ID, LABEL)).orderBy(ID.asc());

    static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    static final ColumnField<O, OrderEntity, Long> ORDER_ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    static final ModelQuery.Builder<OrderEntity, Object, O> ORDERS_QUERY =
            ModelQuery.builder(ORDERS, row -> new O(row.get(ORDER_ID))).select(SelectSet.of(ORDER_ID))
                    .orderBy(ORDER_ID.asc());
    static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS = TableField.join(ORDERS, "items", INNER);
    static final ColumnField<O, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(O.class, ORDER_ITEMS, "productCode", String.class);

    static final FilterOperator<String, String> CONTAINS = HibernateOperators.ilike(LikeMode.CONTAINS);
    static final FilterOperator<String, String> STARTS_WITH = HibernateOperators.ilike(LikeMode.STARTS_WITH);
    static final FilterOperator<String, String> ENDS_WITH = HibernateOperators.ilike(LikeMode.ENDS_WITH);
    static final FilterOperator<String, String> EXACT = HibernateOperators.ilike(LikeMode.EXACT);
    static final FilterOperator<String, String> REGEX = HibernateOperators.regexMatch();

    /** Stores the value as given: a converted twin of {@link #LABEL}. */
    static final class Same implements ColumnConverter<String, String> {
        @Override
        public String toModel(String attribute) {
            return attribute;
        }

        @Override
        public String toAttribute(String model) {
            return model;
        }
    }

    /** Stores {@code "p" + value}, so a pattern built before it would differ from one built after it. */
    static final class Prefixed implements ColumnConverter<String, String> {
        @Override
        public String toModel(String attribute) {
            return attribute.startsWith("p") ? attribute.substring(1) : attribute;
        }

        @Override
        public String toAttribute(String model) {
            return "p" + model;
        }
    }

    // ---- AC-FLT-21: ilike on PostgreSQL and H2

    @Test
    void ac_flt_21_the_factories_are_cached() {
        assertThat(HibernateOperators.ilike(LikeMode.CONTAINS)).isSameAs(CONTAINS).isNotSameAs(STARTS_WITH);
        assertThat(HibernateOperators.regexMatch()).isSameAs(REGEX);
    }

    @TckTest
    void ac_flt_21_ilike_matches_ignoring_case_with_the_value_escaped_as_like_escapes_it(TckDatabase db) {
        assumeIlike(db);
        // Labels: 1 plain, 2 O'Brien, 3 back\slash, 4 NULL, 5 padded, 6 mixed'and\slash.
        List<List<R>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            results.add(rows(ds, f -> f.op(CONTAINS, LABEL, "SLASH")));
            results.add(rows(ds, f -> f.op(CONTAINS, LABEL, "\\")));
            results.add(rows(ds, f -> f.op(STARTS_WITH, LABEL, "o'")));
            results.add(rows(ds, f -> f.op(ENDS_WITH, LABEL, "DED")));
            results.add(rows(ds, f -> f.op(CONTAINS, LABEL, "_")));
            results.add(rows(ds, f -> f.op(CONTAINS, LABEL, "%")));
            // EXACT passes the pattern through: its _ is a wildcard.
            results.add(rows(ds, f -> f.op(EXACT, LABEL, "P_AIN")));
        });
        assertThat(results).extracting(list -> list.stream().map(R::id).toList()).containsExactly(
                List.of(3L, 6L), List.of(3L, 6L), List.of(2L), List.of(5L), List.of(), List.of(), List.of(1L));
    }

    @TckTest
    void ac_flt_21_ilike_in_optional_and_field_form_and_inside_or_not_exists_and_a_sub_select(TckDatabase db) {
        assumeIlike(db);
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            results.add(ids(rows(ds, f -> f.op(EXACT, LABEL, Optional.of("PLAIN")))));
            results.add(ids(rows(ds, f -> f.op(CONTAINS, LABEL, Optional.empty()))));
            // The right column is the pattern as stored. EXACT renders escape '' as like does, so its \ is literal;
            // under CONTAINS it escapes the next character, so 3 and 6 miss.
            results.add(ids(rows(ds, f -> f.op(EXACT, LABEL, LABEL_AGAIN))));
            results.add(ids(rows(ds, f -> f.op(CONTAINS, LABEL, LABEL_AGAIN))));
            results.add(ids(rows(ds, f -> f.or(a -> a.op(STARTS_WITH, LABEL, "PL"),
                    b -> b.op(ENDS_WITH, LABEL, "ED")))));
            // NOT drops the NULL row as well (R-FLT-22).
            results.add(ids(rows(ds, f -> f.not(g -> g.op(CONTAINS, LABEL, "slash")))));
            results.add(ids(rows(ds, f -> f.in(ID, SubSelect.of(ID).where(g -> g.op(CONTAINS, LABEL, "Slash"))))));
            withExecutor(ds, OrderEntity.class, executor -> results.add(executor.list(ORDERS_QUERY.where(
                    f -> f.exists(ORDER_ITEMS, g -> g.op(EXACT, ITEM_PRODUCT, "p007"))).build(), Limit.unlimited())
                    .stream().map(O::id).toList()));
        });
        assertThat(results.subList(0, 7)).containsExactly(List.of(1L), List.of(1L, 2L, 3L, 4L, 5L, 6L),
                List.of(1L, 2L, 3L, 5L, 6L), List.of(1L, 2L, 5L), List.of(1L, 5L), List.of(1L, 2L, 5L),
                List.of(3L, 6L));
        assertThat(results.get(7)).isNotEmpty().isEqualTo(longs(db, "select distinct o.id from OrderEntity o"
                + " join o.items i where i.productCode = 'P007' order by o.id"));
    }

    @TckTest
    void ac_flt_21_an_empty_optional_ilike_creates_no_join(TckDatabase db) {
        // Empty records and renders nothing, so it is never gated either: MySQL runs it too.
        List<Long> counts = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class,
                executor -> counts.add(executor.count(ORDERS_QUERY.where(
                        f -> f.op(CONTAINS, ITEM_PRODUCT, Optional.empty())).build()))));
        assertThat(counts).containsExactly((long) TckFixture.ORDERS);
        assertThat(sql).filteredOn(s -> s.contains("from orders")).singleElement().asString()
                .doesNotContain("order_items");
    }

    @TckTest
    void ac_flt_21_ilike_binds_its_prepared_value_as_a_parameter(TckDatabase db) {
        assumeIlike(db);
        List<List<R>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "flt-21-ilike", ds -> {
            results.add(rows(ds, f -> f.op(CONTAINS, LABEL, "50%_off")));
            results.add(rows(ds, f -> f.op(EXACT, LABEL, "P_AIN")));
        });
        assertThat(results).containsExactly(List.of(), List.of(new R(1L, "plain")));
        assertThat(sql).allSatisfy(s -> assertThat(s).contains("ilike ?").doesNotContain("50").doesNotContain("P_AIN"));
    }

    @TckTest
    void ac_flt_21_ilike_on_a_converted_column_builds_its_pattern_after_the_converter_as_like_does(TckDatabase db) {
        assumeIlike(db);
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            // CONTAINS "a" converts to "pa", then wraps: %pa% matches padded alone. Wrapping first would bind
            // p%a%, which matches plain too.
            results.add(ids(rows(ds, f -> f.like(PREFIXED, "a", LikeMode.CONTAINS))));
            results.add(ids(rows(ds, f -> f.op(CONTAINS, PREFIXED, "A"))));
            results.add(ids(rows(ds, f -> f.op(CONTAINS, PREFIXED, Optional.of("A")))));
            results.add(ids(rows(ds, f -> f.like(PREFIXED, "lain", LikeMode.STARTS_WITH))));
            results.add(ids(rows(ds, f -> f.op(STARTS_WITH, PREFIXED, "LAIN"))));
        });
        assertThat(results).containsExactly(List.of(5L), List.of(5L), List.of(5L), List.of(1L), List.of(1L));
    }

    // ---- AC-FLT-24: regexMatch on PostgreSQL, and both checks elsewhere

    @TckTest
    void ac_flt_24_regex_match_returns_the_matching_rows_on_postgresql(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL, "only PostgreSQL has REGEX_MATCH");
        List<List<Long>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "flt-24-regex-match", ds -> {
            results.add(ids(rows(ds, f -> f.op(REGEX, LABEL, "^p(lain|added)$"))));
            results.add(ids(rows(ds, f -> f.op(REGEX, LABEL, "\\\\slash$"))));
            // Case-sensitive, and the value is the expression, unprepared.
            results.add(ids(rows(ds, f -> f.op(REGEX, LABEL, "SLASH"))));
            results.add(ids(rows(ds, f -> f.not(g -> g.op(REGEX, LABEL, "a")))));
        });
        assertThat(results).containsExactly(List.of(1L, 5L), List.of(3L, 6L), List.of(), List.of(2L));
        // The bare pattern, never compared to true.
        assertThat(sql).allSatisfy(s -> assertThat(s).contains("label ~ ?").doesNotContain("true"));
    }

    @TckTest
    void ac_flt_24_regex_match_pattern_is_never_converted_by_a_column_converter_d174(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL, "only PostgreSQL has REGEX_MATCH");
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            // Prefixed would store the pattern as "p^p(lain|added)$", which matches nothing; the raw one meets the
            // stored labels plain and padded.
            results.add(ids(rows(ds, f -> f.op(REGEX, PREFIXED, "^p(lain|added)$"))));
            results.add(ids(rows(ds, f -> f.op(REGEX, PREFIXED, "\\\\slash$"))));
        });
        assertThat(results).containsExactly(List.of(1L, 5L), List.of(3L, 6L));
    }

    @TckTest
    void ac_flt_24_the_vendor_check_refuses_with_mq4015_before_any_statement(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.POSTGRESQL, "PostgreSQL serves both features");
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, ConstantRowEntity.class, executor -> {
            refused(executor, f -> f.op(REGEX, LABEL, "a"), MqCode.MQ4015, "op(regexMatch)", "REGEX_MATCH");
            if (db.vendor() == TckVendor.MYSQL) {
                refused(executor, f -> f.op(CONTAINS, LABEL, "a"), MqCode.MQ4015, "op(ilike(CONTAINS))", "ILIKE");
            }
        }));
        assertThat(sql).noneMatch(s -> s.contains("constant_rows"));
    }

    @TckTest
    void ac_flt_24_a_profile_answering_true_meets_hibernates_check_mq4014_never_a_database_error(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.POSTGRESQL, "PostgreSQL serves both features");
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                ModelQueryConfig config = ModelQueryConfig.defaults()
                        .vendorProfiles(List.of(OperatorProviderSupport.answering(sf, feature -> true)));
                sf.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, ConstantRowEntity.class, config);
                    // Hibernate has no mq_regex_match here, and ilike only as the lower(...) like emulation on MySQL.
                    refused(executor, f -> f.op(REGEX, LABEL, "a"), MqCode.MQ4014, "op(regexMatch)",
                            "ProviderSupport#operators");
                    if (db.vendor() == TckVendor.MYSQL) {
                        refused(executor, f -> f.op(CONTAINS, LABEL, "a"), MqCode.MQ4014, "op(ilike(CONTAINS))",
                                "ProviderSupport#operators");
                    }
                });
            }
        });
        assertThat(sql).noneMatch(s -> s.contains("constant_rows"));
    }

    // ---- helpers

    private static void assumeIlike(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL has no native ILIKE");
    }

    private static void refused(ModelQueryExecutor<ConstantRowEntity> executor, UnaryOperator<Filters<R>> where,
            MqCode code, String operator, String named) {
        assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(where).build(), Limit.unlimited()))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(operator).hasMessageContaining(named);
    }

    private static List<Long> ids(List<R> rows) {
        return rows.stream().map(R::id).toList();
    }

    private static List<Long> longs(TckDatabase db, String jpql) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            var ids = new ArrayList<Long>();
            sf.inSession(em -> ids.addAll(em.createQuery(jpql, Long.class).getResultList()));
            return ids;
        }
    }

    private static List<R> rows(DataSource ds, UnaryOperator<Filters<R>> where) {
        List<R> results = new ArrayList<>();
        withExecutor(ds, ConstantRowEntity.class,
                executor -> results.addAll(executor.list(ROWS_QUERY.where(where).build(), Limit.unlimited())));
        return results;
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
