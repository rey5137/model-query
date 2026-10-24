package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class JoinFilterValidationTest {

    static final class Order {}
    static final class Customer {}
    static final class View {}
    static final class Source {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Customer> CUSTOMER = TableField.join(ROOT, "customer", JoinType.LEFT);
    private static final OrderedColumnField<View, Order, Long> ID =
            ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final OrderedColumnField<View, Customer, String> NAME =
            ColumnField.of(View.class, CUSTOMER, "name", String.class);
    private static final OrderedColumnField<View, Order, Long> CUSTOMER_ID =
            ColumnField.of(View.class, ROOT, "customer", Long.class);
    private static final OrderedColumnField<Source, Order, Long> SOURCE_ID =
            ColumnField.of(Source.class, ROOT, "id", Long.class).named("id");
    private static final SourceQuery<Source> SOURCE = SourceQuery.builder(Source.class, ROOT,
            SelectSet.of(SOURCE_ID), PrimaryKey.of(SOURCE_ID)).build();
    private static final SourceJoin<View, Source> SOURCE_JOIN =
            SourceJoin.of("source", Source.class, JoinKind.LEFT, List.of(SourceJoin.on(ID, SOURCE_ID)));

    private static ModelQuery.Builder<Order, Long, View> base() {
        return ModelQuery.builder(ROOT, row -> new View()).primaryKey(PrimaryKey.of(ID))
                .select(SelectSet.of(ID, NAME));
    }

    private static void refusedAtBuild(UnaryOperator<Filters<View>> on) {
        var builder = base().joinFilter(CUSTOMER, on);
        refused(builder::build);
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1912");
    }

    @Test
    void ac_col_56_root_column_is_refused_at_build() {
        refusedAtBuild(f -> f.eq(ID, 1L));
    }

    @Test
    void ac_col_56_sibling_column_is_refused_at_build() {
        var sibling = TableField.<Order, Customer>join(ROOT, "billingCustomer", JoinType.LEFT);
        var column = ColumnField.of(View.class, sibling, "name", String.class);
        refusedAtBuild(f -> f.eq(column, "value"));
    }

    @Test
    void ac_col_56_joined_source_column_is_refused_at_build() {
        var column = ColumnField.over(View.class, SOURCE_JOIN.root(), SOURCE_ID);
        var builder = base().joinSource(SOURCE_JOIN, SOURCE).joinFilter(CUSTOMER, f -> f.eq(column, 1L));
        refused(builder::build);
    }

    @Test
    void ac_col_56_to_one_path_below_filtered_join_is_refused_without_a_metamodel() {
        var address = TableField.<Customer, Customer>join(CUSTOMER, "address", JoinType.LEFT);
        var column = ColumnField.of(View.class, address, "name", String.class);
        refusedAtBuild(f -> f.eq(column, "value"));
    }

    @Test
    void ac_col_56_to_many_path_below_filtered_join_is_refused_without_a_metamodel() {
        var orders = TableField.<Customer, Order>join(CUSTOMER, "orders", JoinType.LEFT);
        var column = ColumnField.of(View.class, orders, "id", Long.class);
        refusedAtBuild(f -> f.eq(column, 1L));
    }

    @Test
    void ac_col_56_exists_in_join_filter_is_refused_at_build() {
        refusedAtBuild(f -> f.exists(CUSTOMER));
    }

    @Test
    void ac_col_56_subselect_in_join_filter_is_refused_at_build() {
        refusedAtBuild(f -> f.in(NAME, SubSelect.of(NAME)));
    }

    @Test
    void ac_col_56_opaque_custom_predicate_in_join_filter_is_refused_at_build() {
        refusedAtBuild(f -> f.add((joins, cb) -> cb.conjunction()));
    }

    @Test
    void ac_col_56_aggregate_in_recorded_join_filter_is_refused_at_build() {
        // Filters accepts ScalarField, so an aggregate is already excluded by the public signature.
        var aggregate = Agg.<View>count(CUSTOMER);
        refusedAtBuild(f -> recorded(f, aggregate));
    }

    @Test
    void ac_col_56_window_in_recorded_join_filter_is_refused_at_build() {
        var window = WindowField.of(View.class, WindowFunction.ROW_NUMBER, Long.class, null,
                List.of(NAME), List.of(NAME.asc()), WindowFrame.NONE);
        refusedAtBuild(f -> recorded(f, window));
    }

    private static Filters<View> recorded(Filters<View> filters, SelectField<View, ?> field) {
        return ((FilterGroup<View>) filters).record(Condition.of(Condition.Kind.EQ, field, List.of(1L)),
                ctx -> java.util.Optional.empty());
    }

    @Test
    void ac_col_56_correlated_value_in_join_filter_is_refused_at_build() {
        var value = Correlated.count(View.class, SubSelect.of(NAME),
                (f, outer) -> f.compare(NAME, Op.EQ, outer.column(NAME)));
        refusedAtBuild(f -> f.eq(value, 1L));
    }

    @Test
    void ac_col_56_inner_association_join_is_refused_at_build() {
        var inner = TableField.<Order, Customer>join(ROOT, "customer", JoinType.INNER);
        var column = ColumnField.of(View.class, inner, "name", String.class);
        var builder = base().joinFilter(inner, f -> f.eq(column, "value"));
        refused(builder::build);
    }

    @Test
    void ac_col_56_inner_entity_join_is_refused_at_build() {
        var entity = EntityJoin.of("entity", Customer.class, JoinKind.INNER,
                List.of(EntityJoin.on(ColumnField.over(View.class, SOURCE_JOIN.root(), SOURCE_ID), "id")));
        var column = ColumnField.of(View.class, entity.root(), "name", String.class);
        var builder = base().joinSource(SOURCE_JOIN, SOURCE).joinEntity(entity)
                .joinFilter(entity, f -> f.eq(column, "value"));
        refused(builder::build);
    }

    @Test
    void ac_col_56_unregistered_inner_entity_join_is_still_refused_at_build() {
        var entity = EntityJoin.of("entity", Customer.class, JoinKind.INNER,
                List.of(EntityJoin.on(ColumnField.over(View.class, SOURCE_JOIN.root(), SOURCE_ID), "id")));
        var column = ColumnField.of(View.class, entity.root(), "name", String.class);
        var builder = base().joinFilter(entity, f -> f.eq(column, "value"));
        refused(builder::build);
        var left = EntityJoin.of("entity", Customer.class, JoinKind.LEFT, entity.on());
        assertThatCode(() -> builder.joinFilter(left, f -> f.eq(column, "replacement")).build())
                .doesNotThrowAnyException();
    }

    @Test
    void ac_col_56_unregistered_left_entity_join_waits_until_first_resolution() {
        var entity = EntityJoin.of("entity", Customer.class, JoinKind.LEFT,
                List.of(EntityJoin.on(ColumnField.over(View.class, SOURCE_JOIN.root(), SOURCE_ID), "id")));
        var column = ColumnField.of(View.class, entity.root(), "name", String.class);
        var query = base().joinFilter(entity, f -> f.eq(column, "value")).build();
        refused(() -> query.buildQuery(new Criteria().builder(), Phase.MODEL, RenderOptions.portable()));
    }

    @Test
    void ac_col_56_root_filter_is_refused_at_build() {
        var builder = base().joinFilter(ROOT, f -> f.eq(ID, 1L));
        refused(builder::build);
    }

    @Test
    void ac_col_56_exists_path_crossing_filtered_join_is_refused_at_build() {
        var builder = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value"))
                .where(f -> f.exists(CUSTOMER));
        refused(builder::build);
    }

    @Test
    void ac_col_56_subselect_crossing_filtered_join_is_refused_at_build() {
        var builder = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value"))
                .where(f -> f.in(NAME, SubSelect.of(NAME)));
        refused(builder::build);
    }

    @Test
    void ac_col_56_correlated_subselect_crossing_filtered_join_is_refused_at_build() {
        var value = Correlated.count(View.class, SubSelect.of(NAME),
                (f, outer) -> f.compare(NAME, Op.EQ, outer.column(NAME)));
        var builder = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value"))
                .select(SelectSet.of(ID, NAME, value));
        refused(builder::build);
    }

    @Test
    void ac_col_56_aggregate_filter_cannot_hide_a_crossing_subquery() {
        var count = Agg.<View>count(CUSTOMER).filter(f -> f.exists(CUSTOMER));
        var builder = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value"))
                .select(SelectSet.of(ID, count)).groupBy(ID);
        refused(builder::build);
    }

    @Test
    void ac_col_56_crossing_subquery_in_window_partition_is_refused_at_build() {
        var value = Correlated.count(View.class, SubSelect.of(NAME),
                (f, outer) -> f.compare(NAME, Op.EQ, outer.column(NAME)));
        var window = WindowField.of(View.class, WindowFunction.ROW_NUMBER, Long.class, null,
                List.of(value), List.of(ID.asc()), WindowFrame.NONE);
        var builder = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value"))
                .select(SelectSet.of(ID, NAME, window));
        refused(builder::build);
    }

    @Test
    void ac_col_56_case_conditions_and_branches_cannot_hide_forbidden_reads() {
        var other = Expr.cases(View.class, String.class).when(f -> f.eq(ID, 1L), "yes").otherwise("no");
        refusedAtBuild(f -> f.eq(other, "yes"));
        var value = Correlated.count(View.class, SubSelect.of(NAME),
                (f, outer) -> f.compare(NAME, Op.EQ, outer.column(NAME)));
        var crossing = Expr.cases(View.class, Long.class).when(f -> f.eq(ID, 1L), value).otherwise(0L);
        var builder = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value"))
                .select(SelectSet.of(ID, NAME, crossing));
        refused(builder::build);
    }

    @Test
    void ac_col_56_id_form_is_refused_at_first_resolution_and_names_the_basic_fk_alternative() {
        var query = base().select(SelectSet.of(ID, NAME, CUSTOMER_ID))
                .joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).build();
        var criteria = new Criteria();
        assertThatThrownBy(() -> query.buildQuery(criteria.builder(), Phase.MODEL, RenderOptions.portable()))
                .isInstanceOf(ModelQueryDefinitionException.class).hasMessageContaining("MQ1912")
                .hasMessageContaining("customer").hasMessageContaining("foreign key as a basic column");
    }

    @Test
    void ac_col_56_id_form_selected_only_in_content_is_refused_before_an_optimized_count() {
        var query = base().select(SelectSet.of(ID, NAME, CUSTOMER_ID))
                .joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).build();
        refused(() -> query.buildRootIdCount(new Criteria().builder(), RenderOptions.portable()));
    }

    @Test
    void ac_col_56_converted_id_form_is_also_refused_at_first_resolution() {
        var converted = ColumnField.of(View.class, ROOT, "customer", String.class, Long.class,
                new ColumnConverter<String, Long>() {
                    @Override
                    public String toModel(Long attribute) {
                        return attribute.toString();
                    }

                    @Override
                    public Long toAttribute(String model) {
                        return Long.valueOf(model);
                    }
                });
        var query = base().select(SelectSet.of(ID, NAME, converted))
                .joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).build();
        refused(() -> query.buildQuery(new Criteria().builder(), Phase.MODEL, RenderOptions.portable()));
    }

    @Test
    void ac_col_56_unused_key_is_refused_at_first_resolution_without_creating_a_join() {
        var query = base().select(SelectSet.of(ID)).joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).build();
        var criteria = new Criteria();
        refused(() -> query.buildQuery(criteria.builder(), Phase.MODEL, RenderOptions.portable()));
        assertThat(criteria.joins).hasValue(0);
    }

    @Test
    void ac_col_56_unused_alias_is_refused_at_first_resolution() {
        var alias = CUSTOMER.as("other");
        var column = ColumnField.of(View.class, alias, "name", String.class);
        var query = base().joinFilter(alias, f -> f.eq(column, "value")).build();
        refused(() -> query.buildQuery(new Criteria().builder(), Phase.MODEL, RenderOptions.portable()));
    }

    @Test
    void ac_col_56_unused_join_type_is_refused_at_first_resolution() {
        var inner = TableField.<Order, Customer>join(ROOT, "customer", JoinType.INNER);
        var column = ColumnField.of(View.class, inner, "name", String.class);
        var query = base().select(SelectSet.of(ID, column))
                .joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).build();
        refused(() -> query.buildQuery(new Criteria().builder(), Phase.MODEL, RenderOptions.portable()));
    }

    @Test
    void ac_col_56_own_columns_constants_and_rooted_subqueries_stay_valid() {
        var own = Expr.cases(View.class, String.class).when(f -> f.eq(NAME, "value"), NAME).otherwise("none");
        var query = base().joinFilter(CUSTOMER, f -> f.compare(own, Op.EQ, Expr.value("value")))
                .where(f -> f.eq(ID, 1L).in(ID, SubSelect.of(ID))).build();
        assertThat(query.conditions().joinFilters()).hasSize(1);
    }

    @Test
    void ac_col_56_optimized_count_checks_content_joins_instead_of_requiring_its_own_left_join() {
        var query = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).build();
        assertThatCode(() -> query.buildRootIdCount(new Criteria().builder(), RenderOptions.portable()))
                .doesNotThrowAnyException();
    }

    @Test
    void ac_col_56_whole_entity_and_basic_fk_columns_are_not_id_form_reads() {
        var entity = ColumnField.of(View.class, ROOT, "customer", Customer.class);
        var basic = ColumnField.of(View.class, ROOT, "customerId", Long.class);
        var query = base().select(SelectSet.of(ID, NAME, entity, basic))
                .joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).build();
        assertThatCode(() -> query.buildQuery(new Criteria().builder(), Phase.MODEL, RenderOptions.portable()))
                .doesNotThrowAnyException();
    }

    @Test
    void ac_col_56_id_form_on_another_alias_does_not_read_the_filtered_join() {
        var alias = CUSTOMER.as("filtered");
        var column = ColumnField.of(View.class, alias, "name", String.class);
        var query = base().select(SelectSet.of(ID, column, CUSTOMER_ID))
                .joinFilter(alias, f -> f.eq(column, "value")).build();
        assertThatCode(() -> query.buildQuery(new Criteria().builder(), Phase.MODEL, RenderOptions.portable()))
                .doesNotThrowAnyException();
    }

    @Test
    void ac_col_56_column_below_the_filtered_join_in_its_own_filter_is_refused_at_first_resolution() {
        belowRefused(ColumnField.of(View.class, CUSTOMER, "customer", Long.class)); // id-form
        belowRefused(ColumnField.of(View.class, CUSTOMER, "customer", Customer.class)); // whole entity
    }

    private static <C> void belowRefused(ColumnField<View, Customer, C> column) {
        var query = base().joinFilter(CUSTOMER, f -> f.isNotNull(column)).build();
        var criteria = new Criteria();
        refused(() -> query.buildQuery(criteria.builder(), Phase.MODEL, RenderOptions.portable()));
        assertThat(criteria.joins).hasValue(1); // the filtered join alone, none below it
    }

    @Test
    void ac_col_56_filtered_join_below_a_retyped_parent_is_applied_not_refused() {
        var inner = TableField.<Order, Customer>join(ROOT, "customer", JoinType.INNER);
        var address = TableField.<Customer, Customer>join(inner, "customer", JoinType.LEFT);
        var innerName = ColumnField.of(View.class, inner, "name", String.class);
        var addressName = ColumnField.of(View.class, address, "name", String.class);
        // Read only inside or(...), the parent resolves LEFT, so the filtered join caches under another parent key.
        var query = base().select(SelectSet.of(ID)).joinFilter(address, f -> f.eq(addressName, "x"))
                .where(f -> f.or(g -> g.eq(innerName, "a"), g -> g.eq(addressName, "b"))).build();
        assertThatCode(() -> query.buildQuery(new Criteria().builder(), Phase.MODEL, RenderOptions.portable()))
                .doesNotThrowAnyException();
    }

    @Test
    void ac_col_56_correlated_value_reading_the_filtered_join_through_the_outer_column_is_valid() {
        var status = ColumnField.of(View.class, ROOT, "status", String.class);
        var value = Correlated.count(View.class, SubSelect.of(ID),
                (f, outer) -> f.compare(status, Op.EQ, outer.column(NAME)));
        var query = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).select(SelectSet.of(ID, NAME, value));
        assertThatCode(query::build).doesNotThrowAnyException();
        // A sub-select reading the filtered join's own column still crosses it.
        var crossing = Correlated.count(View.class, SubSelect.of(NAME),
                (f, outer) -> f.compare(status, Op.EQ, outer.column(NAME)));
        var builder = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "value")).select(SelectSet.of(ID, NAME, crossing));
        refused(builder::build);
    }

    private static final class Criteria {
        private final AtomicInteger joins = new AtomicInteger();

        CriteriaBuilder builder() {
            return node(CriteriaBuilder.class, Object.class);
        }

        @SuppressWarnings("unchecked")
        private <T> T node(Class<?> api, Class<?> javaType) {
            return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "toString" -> api.getSimpleName();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "getJavaType" -> javaType;
                    case "createTupleQuery" -> node(CriteriaQuery.class, Object.class);
                    case "from" -> node(Root.class, Order.class);
                    case "getModel" -> entityType(javaType);
                    case "getAttribute" -> association();
                    case "get" -> node(Path.class, args[0].equals("name") ? String.class : Long.class);
                    case "join" -> {
                        joins.incrementAndGet();
                        yield node(Join.class, Customer.class);
                    }
                    case "alias", "on", "multiselect", "where", "orderBy", "groupBy", "having", "distinct" -> proxy;
                    case "isDistinct" -> false;
                    case "getRoots", "getJoins" -> Set.of();
                    case "equal", "and", "conjunction" -> node(Predicate.class, Boolean.class);
                    default -> {
                        Class<?> result = method.getReturnType();
                        if (result.isInterface()) {
                            yield node(result, Long.class);
                        }
                        throw new AssertionError(method.getName());
                    }
                };
            });
        }

        private static EntityType<?> entityType(Class<?> javaType) {
            return meta(EntityType.class, (name, args) -> switch (name) {
                case "getJavaType" -> javaType;
                case "getAttribute" -> args[0].equals("customer") ? association() : id();
                case "hasSingleIdAttribute" -> true;
                case "getIdType" -> basicType();
                case "getId" -> id();
                case "getSingularAttributes" -> Set.of(id());
                default -> throw new AssertionError(name);
            });
        }

        private static SingularAttribute<?, ?> association() {
            return meta(SingularAttribute.class, (name, args) -> switch (name) {
                case "isAssociation" -> true;
                case "getType" -> entityType(Customer.class);
                case "getJavaType" -> Customer.class;
                case "getName" -> "customer";
                default -> throw new AssertionError(name);
            });
        }

        private static SingularAttribute<?, ?> id() {
            return meta(SingularAttribute.class, (name, args) -> switch (name) {
                case "isAssociation" -> false;
                case "isId" -> true;
                case "getName" -> "id";
                case "getJavaType" -> Long.class;
                case "getType" -> basicType();
                default -> throw new AssertionError(name);
            });
        }

        private static Type<?> basicType() {
            return meta(Type.class, (name, args) -> switch (name) {
                case "getPersistenceType" -> Type.PersistenceType.BASIC;
                case "getJavaType" -> Long.class;
                default -> throw new AssertionError(name);
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T meta(Class<?> api, BiFunction<String, Object[], Object> answer) {
            return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api},
                    (proxy, method, args) -> answer.apply(method.getName(), args));
        }
    }
}
