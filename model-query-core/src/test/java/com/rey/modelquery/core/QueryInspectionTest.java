package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Condition.Kind;
import com.rey.modelquery.core.SortSpec.Key;
import jakarta.persistence.criteria.JoinType;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** What a built query records of its filters, read through {@code conditions()} (spec api/16, R-INS-01..05). */
class QueryInspectionTest {

    static final class Order {}

    static final class Item {}

    static final class OrderView {}

    enum Status { NEW, PAID }

    /** Maps a status to its lower-case attribute text, so a recorded value shows whether it was converted. */
    static final class StatusConverter implements ColumnConverter<Status, String> {
        @Override
        public Status toModel(String attribute) {
            return Status.valueOf(attribute.toUpperCase());
        }

        @Override
        public String toAttribute(Status model) {
            return model.name().toLowerCase();
        }
    }

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Item> ITEMS = TableField.join(ROOT, "items", JoinType.INNER);
    private static final OrderedColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final OrderedColumnField<OrderView, Order, String> NAME =
            ColumnField.of(OrderView.class, ROOT, "name", String.class);
    private static final OrderedColumnField<OrderView, Order, Integer> TOTAL =
            ColumnField.of(OrderView.class, ROOT, "total", Integer.class);
    private static final OrderedColumnField<OrderView, Order, Integer> DISCOUNT =
            ColumnField.of(OrderView.class, ROOT, "discount", Integer.class);
    private static final ColumnField<OrderView, Order, Status> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", Status.class, String.class, new StatusConverter());
    private static final ColumnField<OrderView, Item, String> SKU =
            ColumnField.of(OrderView.class, ITEMS, "sku", String.class);
    private static final AggregateField<OrderView, Long> COUNT = Agg.count(ROOT);
    private static final AggregateField<OrderView, Long> SUM = Agg.sum(ID);

    private static ModelQuery<Order, Long, OrderView> where(UnaryOperator<Filters<OrderView>> filters) {
        return ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(ID, NAME, TOTAL))
                .primaryKey(PrimaryKey.of(ID))
                .where(filters)
                .build();
    }

    private static List<Condition> conditions(UnaryOperator<Filters<OrderView>> filters) {
        return where(filters).conditions().where();
    }

    private static Condition only(UnaryOperator<Filters<OrderView>> filters) {
        List<Condition> where = conditions(filters);
        assertThat(where).hasSize(1);
        return where.get(0);
    }

    @Test
    void ac_ins_01_each_filters_operator_records_its_kind_column_and_values() {
        List<Condition> where = conditions(f -> f
                .eq(NAME, "a").ne(NAME, "b").gt(TOTAL, 1).gte(TOTAL, 2).lt(TOTAL, 3).lte(TOTAL, 4)
                .range(TOTAL, Optional.of(5), Optional.of(6)).between(TOTAL, 7, 8)
                .in(TOTAL, List.of(9, 10)).notIn(TOTAL, List.of(11))
                .like(NAME, "c", LikeMode.CONTAINS).likeIgnoreCase(NAME, "D", LikeMode.STARTS_WITH)
                .eqIgnoreCase(NAME, "E").isNull(NAME).isNotNull(TOTAL)
                .compare(TOTAL, Op.GT, DISCOUNT));

        assertThat(where).extracting(Condition::kind).containsExactly(Kind.EQ, Kind.NE, Kind.GT, Kind.GTE, Kind.LT,
                Kind.LTE, Kind.RANGE, Kind.BETWEEN, Kind.IN, Kind.NOT_IN, Kind.LIKE, Kind.LIKE_IGNORE_CASE,
                Kind.EQ_IGNORE_CASE, Kind.IS_NULL, Kind.IS_NOT_NULL, Kind.COMPARE);
        assertThat(where.subList(0, 15)).<Object>extracting(c -> c.column().orElseThrow()).containsExactly(NAME,
                NAME, TOTAL, TOTAL, TOTAL, TOTAL, TOTAL, TOTAL, TOTAL, TOTAL, NAME, NAME, NAME, NAME, TOTAL);
        assertThat(where).extracting(Condition::values).containsExactly(List.of("a"), List.of("b"), List.of(1),
                List.of(2), List.of(3), List.of(4), List.of(5, 6), List.of(7, 8), List.of(9, 10), List.of(11),
                List.of("c"), List.of("D"), List.of("E"), List.of(), List.of(), List.of());
        assertThat(where.get(10).likeMode()).contains(LikeMode.CONTAINS);
        assertThat(where.get(11).likeMode()).contains(LikeMode.STARTS_WITH);
        Condition compare = where.get(15);
        assertThat(compare.column()).contains(TOTAL);
        assertThat(compare.op()).contains(Op.GT);
        assertThat(compare.right()).contains(DISCOUNT);
        assertThat(compare.values()).isEmpty();
        Condition eq = where.get(0);
        assertThat(eq.children()).isEmpty();
        assertThat(eq.right()).isEmpty();
        assertThat(eq.op()).isEmpty();
        assertThat(eq.likeMode()).isEmpty();
        assertThat(eq.path()).isEmpty();
        assertThat(eq.label()).isEmpty();
        assertThatThrownBy(() -> eq.values().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ac_ins_01_or_not_and_exists_record_their_children_and_an_or_branch_of_two_records_an_and() {
        List<Condition> where = conditions(f -> f
                .or(a -> a.eq(NAME, "a").gt(TOTAL, 1), b -> b.isNull(NAME))
                .not(g -> g.eq(NAME, "b"))
                .exists(ITEMS, i -> i.eq(SKU, "s").isNotNull(SKU))
                .notExists(ITEMS, i -> i.eq(SKU, "t"))
                .exists(ITEMS));

        assertThat(where).extracting(Condition::kind)
                .containsExactly(Kind.OR, Kind.NOT, Kind.EXISTS, Kind.NOT_EXISTS, Kind.EXISTS);
        Condition or = where.get(0);
        assertThat(or.column()).isEmpty();
        assertThat(or.children()).extracting(Condition::kind).containsExactly(Kind.AND, Kind.IS_NULL);
        assertThat(or.children().get(0).children()).extracting(Condition::kind).containsExactly(Kind.EQ, Kind.GT);
        assertThat(where.get(1).children()).extracting(Condition::kind).containsExactly(Kind.EQ);
        assertThat(where.get(2).path()).contains(ITEMS);
        assertThat(where.get(2).children()).extracting(Condition::kind).containsExactly(Kind.EQ, Kind.IS_NOT_NULL);
        assertThat(where.get(3).path()).contains(ITEMS);
        assertThat(where.get(3).children()).extracting(Condition::values).containsExactly(List.of("t"));
        assertThat(where.get(4).path()).contains(ITEMS);
        assertThat(where.get(4).children()).isEmpty();
        // An or of three single filters and an or of a two-filter branch and a single one differ (D-101).
        assertThat(only(f -> f.or(a -> a.eq(NAME, "a"), b -> b.gt(TOTAL, 1), c -> c.isNull(NAME))))
                .isNotEqualTo(only(f -> f.or(a -> a.eq(NAME, "a").gt(TOTAL, 1), c -> c.isNull(NAME))));
        // when and apply record nothing of their own: their filters join the enclosing group.
        assertThat(conditions(f -> f.when(true, g -> g.eq(NAME, "a")).apply(g -> g.gt(TOTAL, 1))))
                .extracting(Condition::kind).containsExactly(Kind.EQ, Kind.GT);
    }

    @Test
    void ac_ins_01_a_one_sided_range_or_between_records_the_comparison_it_renders() {
        Optional<Integer> none = Optional.empty();
        Condition from = only(f -> f.range(TOTAL, Optional.of(1), none));
        assertThat(from.kind()).isEqualTo(Kind.GTE);
        assertThat(from.values()).containsExactly(1);
        assertThat(only(f -> f.range(TOTAL, none, Optional.of(2))).kind()).isEqualTo(Kind.LT);
        assertThat(only(f -> f.between(TOTAL, Optional.of(3), none)).kind()).isEqualTo(Kind.GTE);
        Condition to = only(f -> f.between(TOTAL, none, Optional.of(4)));
        assertThat(to.kind()).isEqualTo(Kind.LTE);
        assertThat(to.values()).containsExactly(4);
        Condition both = only(f -> f.between(TOTAL, Optional.of(5), Optional.of(6)));
        assertThat(both.kind()).isEqualTo(Kind.BETWEEN);
        assertThat(both.values()).containsExactly(5, 6);
    }

    @Test
    void ac_ins_01_values_are_recorded_as_passed_before_the_converter_and_not_lower_cased() {
        List<Condition> where = conditions(f -> f
                .eq(STATUS, Status.PAID).in(STATUS, List.of(Status.NEW, Status.PAID))
                .eqIgnoreCase(NAME, "MiXeD").likeIgnoreCase(NAME, "50%_Off", LikeMode.CONTAINS));

        assertThat(where).extracting(Condition::values).containsExactly(List.of(Status.PAID),
                List.of(Status.NEW, Status.PAID), List.of("MiXeD"), List.of("50%_Off"));
    }

    @Test
    void ac_ins_01_having_records_the_same_kinds_over_aggregate_columns() {
        var query = ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(NAME, COUNT))
                .groupBy(NAME)
                .having(h -> h.gt(COUNT, 1L).between(SUM, 2L, 3L).in(COUNT, List.of(4L))
                        .or(a -> a.isNull(SUM), b -> b.lt(SUM, 5L).gte(COUNT, 6L))
                        .not(g -> g.eq(COUNT, 7L)).compare(SUM, Op.GTE, COUNT))
                .build();

        QueryConditions conditions = query.conditions();
        assertThat(conditions.where()).isEmpty();
        List<Condition> having = conditions.having();
        assertThat(having).extracting(Condition::kind)
                .containsExactly(Kind.GT, Kind.BETWEEN, Kind.IN, Kind.OR, Kind.NOT, Kind.COMPARE);
        assertThat(having.get(0).column()).contains(COUNT);
        assertThat(having.get(1).values()).containsExactly(2L, 3L);
        assertThat(having.get(3).children()).extracting(Condition::kind).containsExactly(Kind.IS_NULL, Kind.AND);
        assertThat(having.get(5).right()).contains(COUNT);
    }

    @Test
    void ac_ins_01_every_kind_is_recorded_by_some_operator() {
        SubSelect<OrderView, String> sub = SubSelect.of(NAME);
        Set<Kind> seen = EnumSet.noneOf(Kind.class);
        collect(seen, conditions(f -> f
                .eq(NAME, "a").ne(NAME, "b").gt(TOTAL, 1).gte(TOTAL, 2).lt(TOTAL, 3).lte(TOTAL, 4)
                .range(TOTAL, Optional.of(5), Optional.of(6)).between(TOTAL, 7, 8)
                .in(TOTAL, List.of(9)).notIn(TOTAL, List.of(10))
                .in(NAME, sub).notIn(NAME, sub)
                .exists(sub, (s, outer) -> s.eq(outer.column(NAME), "x"))
                .notExists(sub, (s, outer) -> s.isNull(outer.column(NAME)))
                .like(NAME, "c", LikeMode.EXACT).likeIgnoreCase(NAME, "d", LikeMode.EXACT).eqIgnoreCase(NAME, "e")
                .isNull(NAME).isNotNull(NAME).compare(TOTAL, Op.EQ, DISCOUNT)
                .or(a -> a.eq(NAME, "f").eq(NAME, "g"), b -> b.isNull(TOTAL)).not(g -> g.eq(NAME, "h"))
                .exists(ITEMS).notExists(ITEMS, i -> i.eq(SKU, "i"))
                .add((ctx, cb) -> cb.conjunction())));

        assertThat(seen).containsExactlyInAnyOrder(Kind.values());
    }

    private static void collect(Set<Kind> into, List<Condition> conditions) {
        for (Condition condition : conditions) {
            into.add(condition.kind());
            collect(into, condition.children());
        }
    }

    @Test
    void ac_ins_02_skipped_filters_record_nothing() {
        Optional<String> none = Optional.empty();
        Optional<Integer> noNumber = Optional.empty();
        assertThat(conditions(f -> f.eq(NAME, Optional.of("a")).gt(TOTAL, noNumber)))
                .extracting(Condition::kind).containsExactly(Kind.EQ);
        assertThat(conditions(f -> f.or(a -> a.eq(NAME, none), b -> b.gt(TOTAL, noNumber)))).isEmpty();
        assertThat(conditions(f -> f.not(g -> g.eq(NAME, none)))).isEmpty();
        assertThat(conditions(f -> f.exists(ITEMS, i -> i.eq(SKU, none)))).isEmpty();
        assertThat(conditions(f -> f.range(TOTAL, noNumber, noNumber).isNull(NAME, Optional.empty()))).isEmpty();
        // A skipped branch is dropped from the or, which keeps the others.
        Condition or = only(f -> f.or(a -> a.eq(NAME, none), b -> b.eq(NAME, "b")));
        assertThat(or.children()).extracting(Condition::kind).containsExactly(Kind.EQ);
    }

    @Test
    void ac_ins_02_an_empty_in_not_in_or_records_its_kind_with_no_values_or_children() {
        List<Condition> where = conditions(f -> f.in(TOTAL, List.of()).notIn(TOTAL, List.of()).or(List.of()));

        assertThat(where).extracting(Condition::kind).containsExactly(Kind.IN, Kind.NOT_IN, Kind.OR);
        assertThat(where.get(0).column()).contains(TOTAL);
        assertThat(where.get(0).values()).isEmpty();
        assertThat(where.get(1).column()).contains(TOTAL);
        assertThat(where.get(1).values()).isEmpty();
        assertThat(where.get(2).children()).isEmpty();
    }

    @Test
    void ac_ins_03_add_records_its_label_and_an_unlabelled_add_none() {
        List<Condition> where = conditions(f -> f
                .add("region visible to user", (ctx, cb) -> cb.conjunction())
                .add((ctx, cb) -> cb.conjunction()));

        assertThat(where).extracting(Condition::kind).containsExactly(Kind.CUSTOM, Kind.CUSTOM);
        assertThat(where.get(0).label()).contains("region visible to user");
        assertThat(where.get(1).label()).isEmpty();
        assertThat(where.get(0).column()).isEmpty();
        assertThat(where.get(0).values()).isEmpty();
    }

    @Test
    void ac_ins_03_a_null_or_blank_label_is_mq1301() {
        for (String label : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> where(f -> f.add(label, (ctx, cb) -> cb.conjunction())))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301));
        }
    }

    @Test
    void ac_ins_04_to_string_and_the_build_log_show_the_column_and_a_question_mark_not_the_value() {
        var builder = ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(ID, NAME))
                .primaryKey(PrimaryKey.of(ID))
                .where(f -> f.eq(NAME, "jane@example.com").in(TOTAL, List.of(41, 42))
                        .or(a -> a.like(NAME, "secret", LikeMode.CONTAINS), b -> b.isNull(NAME))
                        .exists(ITEMS, i -> i.eq(SKU, "sku-1234"))
                        .add("region visible to user", (ctx, cb) -> cb.conjunction()));
        List<String> log = new ArrayList<>();
        ModelQuery<Order, Long, OrderView> query = debugLog(log, builder::build);

        String text = query.conditions().toString();
        assertThat(text).isEqualTo("where [EQ(OrderView.name, ?), IN(OrderView.total, ? x 2), "
                + "OR(LIKE(OrderView.name, ?, CONTAINS), IS_NULL(OrderView.name)), "
                + "EXISTS(join 'items' (INNER), EQ(OrderView.sku, ?)), CUSTOM(\"region visible to user\")], having []");
        assertThat(query.conditions().where().get(0)).hasToString("EQ(OrderView.name, ?)");
        assertThat(log).singleElement().asString()
                .contains("where [EQ(OrderView.name, ?), IN(OrderView.total, ? x 2)", "region visible to user");
        for (String shown : List.of(text, log.get(0))) {
            assertThat(shown).doesNotContain("jane@example.com", "41", "42", "secret", "sku-1234");
        }
        assertThat(query).hasToString("OrderView");
    }

    @Test
    void r_ins_04_the_view_is_a_value_shared_by_copies() {
        UnaryOperator<Filters<OrderView>> filters = f -> f.eq(NAME, "a")
                .exists(ITEMS, i -> i.eq(SKU, "s"))
                .add("region", (ctx, cb) -> cb.conjunction());
        var query = where(filters);
        // Another path instance with the same key, and another custom lambda with the same label.
        TableField<Order, Item> items = TableField.join(ROOT, "items", JoinType.INNER);
        ColumnField<OrderView, Item, String> sku = ColumnField.of(OrderView.class, items, "sku", String.class);
        var same = where(f -> f.eq(NAME, "a")
                .exists(items, i -> i.eq(sku, "s"))
                .add("region", (ctx, cb) -> cb.disjunction()));

        assertThat(same.conditions()).isEqualTo(query.conditions()).hasSameHashCodeAs(query.conditions());
        assertThat(where(f -> f.add("other", (ctx, cb) -> cb.conjunction())).conditions())
                .isNotEqualTo(where(f -> f.add("region", (ctx, cb) -> cb.conjunction())).conditions());
        assertThat(where(f -> f.eq(NAME, "b")).conditions()).isNotEqualTo(where(f -> f.eq(NAME, "a")).conditions());
        assertThat(where(f -> f.exists(ITEMS.as("other"))).conditions())
                .isNotEqualTo(where(f -> f.exists(ITEMS)).conditions());
        assertThat(query.orderedBy(SortSpec.of(Key.desc("name"))).conditions()).isSameAs(query.conditions());
        assertThat(query.withFetch(FetchPlan.of(SelectSet.of(ID, NAME))).conditions())
                .isEqualTo(query.conditions());
        assertThatThrownBy(() -> query.conditions().where().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Runs {@code work}, adding to {@code into} the messages {@code ModelQuery} logs at DEBUG (FINE on JUL). */
    private static <T> T debugLog(List<String> into, Supplier<T> work) {
        Logger logger = Logger.getLogger(ModelQuery.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.FINE) {
                    // Formatted only with parameters, as JUL does: the message itself may quote a join's name.
                    into.add(r.getParameters() == null ? r.getMessage()
                            : MessageFormat.format(r.getMessage(), r.getParameters()));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Level before = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            return work.get();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(before);
        }
    }
}
