package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * What a query loads beyond its own row: its selection, the children to load, plans for {@code @Join}ed models and
 * enrichers, each in the order added. Immutable: {@code child}, {@code join} and {@code enrich} return copies
 * (INV-9). A plan nests to any depth, and is built bottom-up from immutable parts, so it cannot contain itself.
 *
 * @param <M> the model the plan fills
 * @implSpec R-FCH-01, R-FCH-02, R-FCH-07
 */
@Incubating
public final class FetchPlan<M> {

    private final SelectSet<M> select;
    private final List<ChildLoad<M, ?>> children;
    private final List<JoinPlan<M, ?>> joins;
    private final List<Enricher<M>> enrichers;

    private FetchPlan(SelectSet<M> select, List<ChildLoad<M, ?>> children, List<JoinPlan<M, ?>> joins,
            List<Enricher<M>> enrichers) {
        this.select = select;
        this.children = children;
        this.joins = joins;
        this.enrichers = enrichers;
    }

    /** A plan selecting {@code select}, with no child, join plan or enricher. */
    public static <M> FetchPlan<M> of(SelectSet<M> select) {
        return new FetchPlan<>(Objects.requireNonNull(select, "select"), List.of(), List.of(), List.of());
    }

    /**
     * A copy that loads the child {@code field} with {@code plan}, ordered by the child's primary key.
     *
     * @throws ModelQueryDefinitionException {@code MQ1703} when the plan already loads {@code field}, and as
     *     {@code build()} does for the child query
     */
    public <C> FetchPlan<M> child(ChildField<M, C> field, FetchPlan<C> plan) {
        return child(field, plan, UnaryOperator.identity());
    }

    /**
     * A copy that loads the child {@code field} with {@code plan}, filtered, ordered and bounded by what
     * {@code query} makes of an empty {@link ChildQuery} (R-FCH-04). The child query is built and checked here, as
     * {@link ModelQuery.Builder#build()} checks a query.
     *
     * @throws ModelQueryDefinitionException {@code MQ1703} when the plan already loads {@code field}, and as
     *     {@code build()} does for the child query
     */
    public <C> FetchPlan<M> child(ChildField<M, C> field, FetchPlan<C> plan, UnaryOperator<ChildQuery<C>> query) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(query, "query");
        for (ChildLoad<M, ?> child : children) {
            if (child.field().name().equals(field.name())) {
                throw twice(field.key().model(), field.name(), "child");
            }
        }
        ChildQuery<C> load = Objects.requireNonNull(query.apply(ChildQuery.empty(childRootEntity(plan))),
                "query result");
        return new FetchPlan<>(select, append(children, new ChildLoad<>(field, plan, load)), joins, enrichers);
    }

    /** The entity {@code plan} selects from, from its first column; {@code null} when it selects only aggregates. */
    private static Class<?> childRootEntity(FetchPlan<?> plan) {
        for (SelectField<?, ?> field : plan.select().fields()) {
            if (field instanceof ColumnField<?, ?, ?> column) {
                return column.table().pathRoot();
            }
        }
        return null;
    }

    /**
     * A copy that applies {@code plan} to the models {@code field} holds. The nested plan's selection and the columns
     * it needs are re-rooted under the join and selected with this plan's own (R-FCH-07).
     *
     * @throws ModelQueryDefinitionException {@code MQ1703} when the plan already has a plan for {@code field}
     */
    public <N> FetchPlan<M> join(JoinField<M, N> field, FetchPlan<N> plan) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(plan, "plan");
        for (JoinPlan<M, ?> join : joins) {
            if (join.field().name().equals(field.name())) {
                throw twice(field.model(), field.name(), "join");
            }
        }
        return new FetchPlan<>(select, children, append(joins, new JoinPlan<>(field, plan)), enrichers);
    }

    /** A copy that runs {@code enricher} after the plan's children, join plans and earlier enrichers (R-FCH-08). */
    public FetchPlan<M> enrich(Enricher<M> enricher) {
        return new FetchPlan<>(select, children, joins,
                append(enrichers, Objects.requireNonNull(enricher, "enricher")));
    }

    /** The plan's own selection, as given to {@link #of}. */
    public SelectSet<M> select() {
        return select;
    }

    /** The children the plan loads, in the order added (R-FCH-04). */
    @EngineFacing
    public List<ChildLoad<M, ?>> childLoads() {
        return children;
    }

    /** The plans of {@code @Join}ed models, in the order added (R-FCH-07). */
    @EngineFacing
    public List<JoinPlan<M, ?>> joinPlans() {
        return joins;
    }

    /** The enrichers, in the order added (R-FCH-08). */
    @EngineFacing
    public List<Enricher<M>> enrichers() {
        return enrichers;
    }

    /**
     * Whether the plan only selects: it has no child, join plan nor enricher, so nothing of it runs on a page
     * (R-FCH-09).
     */
    @EngineFacing
    public boolean isSelectionOnly() {
        return children.isEmpty() && joins.isEmpty() && enrichers.isEmpty();
    }

    /** A copy that also selects {@code column}, as a child query selects its foreign key (R-FCH-05). */
    FetchPlan<M> selecting(ColumnField<M, ?, ?> column) {
        return new FetchPlan<>(select.with(column), children, joins, enrichers);
    }

    /**
     * What a query with this plan selects: the plan's own selection, then each join plan's re-rooted under its join,
     * recursively (R-FCH-07).
     *
     * @throws ModelQueryDefinitionException {@code MQ1705} for a join plan selecting an aggregate
     */
    SelectSet<M> selection() {
        var result = new ArrayList<SelectField<M, ?>>(select.fields());
        for (JoinPlan<M, ?> join : joins) {
            result.addAll(join.selection());
        }
        return SelectSet.copyOf(result);
    }

    /**
     * The columns the plan needs besides its selection: each child's key and each enricher's columns, then each join
     * plan's, re-rooted under its join (R-FCH-02).
     */
    List<ColumnField<M, ?, ?>> needed() {
        var result = new ArrayList<ColumnField<M, ?, ?>>();
        children.forEach(child -> result.add(child.field().key()));
        enrichers.forEach(enricher -> result.addAll(enricher.columns()));
        for (JoinPlan<M, ?> join : joins) {
            result.addAll(join.needed());
        }
        return result;
    }

    /** The first child loaded at any join depth, as its field path from this model, or {@code null} (R-FCH-10). */
    String firstChild() {
        if (!children.isEmpty()) {
            return children.get(0).field().name();
        }
        for (JoinPlan<M, ?> join : joins) {
            String inner = join.plan().firstChild();
            if (inner != null) {
                return join.field().name() + "." + inner;
            }
        }
        return null;
    }

    /** Each join with a plan at any depth, by its field path, re-rooted to this plan's model (R-FCH-07). */
    Map<String, TableField<?, ?>> joinTables() {
        var result = new LinkedHashMap<String, TableField<?, ?>>();
        for (JoinPlan<M, ?> join : joins) {
            TableField<?, ?> table = join.field().table();
            result.put(join.field().name(), table);
            join.plan().joinTables().forEach((path, inner) -> result.put(join.field().name() + "." + path,
                    inner.under(table)));
        }
        return result;
    }

    private static ModelQueryDefinitionException twice(Class<?> model, String name, String kind) {
        return new ModelQueryDefinitionException(MqCode.MQ1703, model.getSimpleName() + "." + name + ": the fetch "
                + "plan names this " + kind + " twice; pass one plan for it, with everything it loads");
    }

    private static <T> List<T> append(List<T> list, T element) {
        var result = new ArrayList<T>(list);
        result.add(element);
        return List.copyOf(result);
    }
}
