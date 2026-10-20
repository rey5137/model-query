package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Per-call statement sites and provider refusals before any statement (R-COL-35, R-VND-26). */
@EngineFacing
@Incubating
public final class TreatChecks {

    /** The conditions that also accept a NULL column through a D-5 {@code IS NULL} branch (R-FLT-04). */
    private static final Set<Condition.Kind> NULL_ACCEPTING = EnumSet.of(Condition.Kind.NE, Condition.Kind.NOT_IN,
            Condition.Kind.NOT_IN_FIELDS, Condition.Kind.NOT_IN_SUBSELECT);

    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> checked = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Set<TreatSite> sites = new LinkedHashSet<>();
    private final Map<String, Class<?>> entities = new HashMap<>();
    private final TreatRenderer renderer;
    private final Metamodel metamodel;
    private final boolean laterPages;
    private final boolean fetchesModels;
    private StatementShape shape;
    private boolean anchored;
    private boolean joinsOnly;
    /** Inside the counted column of a correlated count, whose own treated root R-COL-35 leaves admitted. */
    private boolean correlatedCount;
    /** Only finding whether the statement has a treated node, for {@link #treated(ModelQuery)}. */
    private boolean detecting;
    private boolean found;
    /** The treated reads found so far, equal sites counted each time. */
    private int occurrences;
    private String owner;
    private Set<TreatUse> uses = EnumSet.noneOf(TreatUse.class);

    private TreatChecks(TreatRenderer renderer, Metamodel metamodel, StatementShape shape,
            boolean laterPages, boolean fetchesModels) {
        this.renderer = renderer;
        this.metamodel = metamodel;
        this.shape = shape;
        this.anchored = shape == StatementShape.CONTENT || shape == StatementShape.COUNT;
        this.laterPages = laterPages;
        this.fetchesModels = fetchesModels;
    }

    /**
     * Checks a read or write definition and returns every successfully scanned definition, including fetch children,
     * for the executor's cache (INV-9, AC-VND-16). A failed scan returns no verdicts.
     */
    @Incubating
    public static Set<Object> check(Object definition) {
        var checks = new TreatChecks(null, null, StatementShape.CONTENT, false, true);
        checks.definition(Objects.requireNonNull(definition, "definition"));
        return Set.copyOf(checks.checked);
    }

    /**
     * Checks every site of a call, including future keyset pages and fetch statements, without executing it
     * (R-VND-26). Sites are returned in encounter order; no provider verdict is cached.
     */
    @Incubating
    public static List<TreatSite> check(Object definition, RenderOptions options, Metamodel metamodel,
            StatementShape shape, boolean laterPages, boolean fetchesModels) {
        var checks = new TreatChecks(Objects.requireNonNull(options, "options").treatRenderer().orElse(null),
                metamodel, Objects.requireNonNull(shape, "shape"), laterPages, fetchesModels);
        checks.definition(Objects.requireNonNull(definition, "definition"));
        return List.copyOf(checks.sites);
    }

    /**
     * Whether {@code query}'s own statement has a treated node: a {@code treat} node or a downcast join in its
     * selection, order, grouping or filters, its sub-selects included, but not in its source's body or its fetch plan's
     * child statements (R-COL-35). Such a statement selects the untreated root id and is counted by it.
     */
    @EngineFacing
    @Incubating
    public static boolean treated(ModelQuery<?, ?, ?> query) {
        var checks = new TreatChecks(null, null, StatementShape.CONTENT, false, false);
        checks.detecting = true;
        checks.statement(StatementShape.CONTENT, true, () -> query.checkTreats(checks));
        return checks.found;
    }

    /**
     * Whether {@code source}'s own body has a treated node in its fields, key or filters, not in an inner source or a
     * branch: such an anchoring body selects the untreated root id (R-SRC-20).
     */
    static boolean treated(SourceQuery<?> source) {
        var checks = new TreatChecks(null, null, StatementShape.SOURCE_BODY, false, false);
        checks.detecting = true;
        checks.statement(StatementShape.SOURCE_BODY, true, () -> source.checkTreatBody(checks));
        return checks.found;
    }

    /**
     * The name of {@code entity}'s single id attribute, which the hidden anchor selects and the count counts
     * (R-COL-35).
     *
     * @throws ModelQueryConfigurationException {@code MQ4016} for an {@code @IdClass} or {@code @EmbeddedId} id: the
     *     anchor and the root-id count were measured over a single id attribute only (R-VND-26)
     */
    static String rootId(EntityType<?> entity, String owner) {
        return rootIdAttribute(entity, owner).getName();
    }

    /** {@code entity}'s single basic id attribute: see {@link #rootId}. */
    static SingularAttribute<?, ?> rootIdAttribute(EntityType<?> entity, String owner) {
        if (entity.hasSingleIdAttribute()) {
            for (SingularAttribute<?, ?> attribute : entity.getSingularAttributes()) {
                if (attribute.isId() && attribute.getType().getPersistenceType() == Type.PersistenceType.BASIC) {
                    return attribute;
                }
            }
        }
        throw new ModelQueryConfigurationException(MqCode.MQ4016, owner + ": " + entity.getJavaType().getSimpleName()
                + " has a composite id, and a treated statement selects and counts a single root id attribute");
    }

    /** Whether {@code fields} select {@code idName} on the query's root, untreated (R-COL-35). */
    static boolean selectsRootId(List<? extends SelectField<?, ?>> fields, String idName) {
        for (SelectField<?, ?> field : fields) {
            if (field instanceof ColumnField<?, ?, ?> column && !column.isLifted()
                    && column.table().key().parent() == null && column.name().equals(idName)) {
                return true;
            }
        }
        return false;
    }

    boolean countOnly() {
        return renderer != null && shape == StatementShape.COUNT;
    }

    boolean fetchesModels() {
        return !detecting && (renderer == null || fetchesModels);
    }

    void statement(StatementShape statement, boolean anchor, Runnable scan) {
        StatementShape previousShape = shape;
        boolean previousAnchor = anchored;
        Set<TreatUse> previousUses = uses;
        shape = statement;
        anchored = anchor;
        uses = EnumSet.noneOf(TreatUse.class);
        try {
            scan.run();
        } finally {
            shape = previousShape;
            anchored = previousAnchor;
            uses = previousUses;
        }
    }

    void using(TreatUse use, Runnable scan) {
        Set<TreatUse> previous = uses;
        var nested = EnumSet.noneOf(TreatUse.class);
        nested.addAll(uses);
        nested.add(use);
        uses = nested;
        try {
            scan.run();
        } finally {
            uses = previous;
        }
    }

    void joins(SelectField<?, ?> field) {
        boolean previous = joinsOnly;
        joinsOnly = true;
        try {
            field(field);
        } finally {
            joinsOnly = previous;
        }
    }

    /**
     * A keyset key's sites on the pages after the first, which page 1 renders none of (R-VND-26): after a non-NULL
     * cursor value R-PAG-06's OR-expansion, {@code {PREDICATE, DISJUNCTION}}, adding {@code NULL_TEST} under an
     * explicit null precedence; after a NULL cursor value, which only an explicit precedence admits, a top-level
     * {@code {NULL_TEST}}.
     */
    void laterKey(OrderField<?, ?> order) {
        if (!laterPages || renderer == null) {
            return;
        }
        SelectField<?, ?> field = order.column();
        using(TreatUse.PREDICATE, () -> using(TreatUse.DISJUNCTION, () -> field(field)));
        if (order.nulls() != NullPrecedence.DEFAULT) {
            using(TreatUse.PREDICATE, () -> using(TreatUse.DISJUNCTION,
                    () -> using(TreatUse.NULL_TEST, () -> field(field))));
            using(TreatUse.NULL_TEST, () -> field(field));
        }
    }

    /** The counted column of a correlated count, admitted over the sub-select's own treated root (R-COL-31). */
    void correlatedCount(SelectField<?, ?> column) {
        boolean previous = correlatedCount;
        correlatedCount = true;
        try {
            using(TreatUse.AGGREGATE, () -> field(column));
        } finally {
            correlatedCount = previous;
        }
    }

    /**
     * Whether a correlated count's own root is treated: its counted column reads from a treat at the top of the
     * sub-select's path, so the count narrows to the subtype rows (R-COL-31). A treat below a join is not the
     * sub-select's root.
     */
    boolean ownTreatedRoot(SubSelect<?, ?> rows) {
        for (JoinKey node = rows.column().table().key(); node != null && node.parent() != null; node = node.parent()) {
            if (node.parent().parent() == null) {
                return node.type() == null;
            }
        }
        return false;
    }

    void definition(Object definition) {
        if (!seen.add(definition)) {
            return;
        }
        String previousOwner = owner;
        owner = definition.toString();
        if (definition instanceof ModelQuery<?, ?, ?> query) {
            entities.put(query.rootEntity().getName(), query.rootEntity());
            // A grouped statement is GROUPED, and the count of its groups GROUPED_COUNT: the engine counts the groups
            // client-side when the renderer refuses that shape (R-EXE-03, D-159).
            StatementShape statement = !query.isGrouped() || shape == StatementShape.CHILD ? shape
                    : shape == StatementShape.COUNT ? StatementShape.GROUPED_COUNT : StatementShape.GROUPED;
            // Only a content statement and a count are anchored: the engine selects or counts the untreated root id
            // there (R-COL-35); a grouped, child or write statement reads its root as written.
            boolean anchor = statement == StatementShape.CONTENT || statement == StatementShape.COUNT;
            if (anchor && metamodel != null && !detecting && treated(query)) {
                rootId(metamodel.entity(query.rootEntity()), owner);
            }
            statement(statement, anchor, () -> query.checkTreats(this));
        } else if (definition instanceof ModelUpdate<?, ?> update) {
            statement(StatementShape.WRITE, false, () -> update.checkTreats(this));
        } else if (definition instanceof ModelDelete<?, ?> delete) {
            statement(StatementShape.WRITE, false, () -> delete.checkTreats(this));
        } else if (definition instanceof ModelInsert<?, ?> insert) {
            statement(StatementShape.WRITE, false, () -> insert.checkTreats(this));
        } else if (definition instanceof ModelPersist<?, ?, ?> persist) {
            statement(StatementShape.WRITE, false, () -> persist.checkTreats(this));
        } else {
            throw new IllegalArgumentException("Not a query or write definition: " + definition.getClass().getName());
        }
        checked.add(definition);
        owner = previousOwner;
    }

    void field(SelectField<?, ?> field) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            if (column.isLifted()) {
                using(TreatUse.SUBQUERY, () -> using(TreatUse.CORRELATION,
                        () -> field(column.liftedFrom().orElseThrow())));
            } else {
                register(column.table());
                table(column.table().key(), Optional.of(column.name()));
            }
        } else if (field instanceof ExpressionField<?, ?> expression) {
            expression.checkTreats(this);
        } else if (field instanceof AggregateField<?, ?> aggregate) {
            using(TreatUse.AGGREGATE, () -> aggregate.checkTreats(this));
        } else if (field instanceof WindowField<?, ?> window) {
            window.checkTreats(this);
        }
    }

    private void register(TableField<?, ?> table) {
        for (; table != null; table = table.parent()) {
            Class<?> entity = table.treatedType() == null ? table.rootEntity() : table.treatedType();
            if (entity != null) {
                entities.put(entity.getName(), entity);
            }
        }
    }

    void table(JoinKey key) {
        table(key, Optional.empty());
    }

    void table(JoinKey key, Optional<String> attribute) {
        boolean narrowing = false;
        for (JoinKey node = key; node != null && node.parent() != null; node = node.parent()) {
            narrowing |= node.type() != null && node.type() != JoinType.LEFT;
        }
        if (joinsOnly && !narrowing) {
            return;
        }
        Optional<String> path = attribute;
        for (JoinKey node = key; node != null && node.parent() != null; node = node.parent()) {
            if (node.type() != null) {
                String joined = node.attribute();
                path = Optional.of(path.map(value -> joined + "." + value).orElse(joined));
                continue;
            }
            if (detecting) {
                found = true;
                return;
            }
            if (renderer == null) {
                throw new ModelQueryConfigurationException(MqCode.MQ4014, node.attribute()
                        + ": a treated path needs the provider's TreatRenderer (ProviderSupport#treats(emf))");
            }
            var siteUses = EnumSet.noneOf(TreatUse.class);
            if (!joinsOnly) {
                siteUses.addAll(uses);
            }
            JoinKey parent = node.parent();
            if (parent.type() != null) {
                siteUses.add(parent.type() == JoinType.LEFT ? TreatUse.LEFT_JOIN : TreatUse.INNER_JOIN);
            } else if (joinsOnly) {
                siteUses.add(TreatUse.INNER_JOIN);
            }
            var site = new TreatSite(entity(parent), entity(node), joinsOnly ? Optional.empty() : path,
                    shape, anchored, siteUses);
            occurrences++;
            admit(site, site.attribute().orElse(site.subtype().getSimpleName()));
        }
    }

    /**
     * Records {@code site} and asks the renderer about it the first time, {@code MQ4016} naming {@code read} when the
     * engine or the renderer refuses it. The engine's refusal is decided per occurrence: an equal site may be
     * admitted in a correlated count.
     */
    private void admit(TreatSite site, String read) {
        boolean added = sites.add(site);
        Optional<String> refused = engineRefusal(site);
        if (refused.isEmpty() && added) {
            refused = renderer.refusal(site);
        }
        refused.ifPresent(reason -> {
            throw new ModelQueryConfigurationException(MqCode.MQ4016, owner + "." + read + ": " + shape + " "
                    + site.uses() + " on " + strategy(site.entity()) + ": " + reason);
        });
    }

    /**
     * A {@code typeOf} read of {@code table}'s node: the treats on its path, then its own {@code TYPE} site, whose
     * entity and subtype are both the node's entity, with no attribute and the node's join, if any (R-COL-36,
     * R-VND-26). A type read makes no treated statement, so it is neither found nor counted as a treated node.
     *
     * @throws ModelQueryConfigurationException {@code MQ4014} without a renderer; {@code MQ4016} over a treated node,
     *     a treat of the root or a downcast join, which no cell measures
     */
    void typeOf(TableField<?, ?> table) {
        register(table);
        table(table.key());
        if (joinsOnly || detecting) {
            return; // a count drops the selection and the order: the type read is not in it
        }
        if (renderer == null) {
            throw new ModelQueryConfigurationException(MqCode.MQ4014, "typeOf(" + table.describe()
                    + "): a type read needs the provider's TreatRenderer (ProviderSupport#treats(emf))");
        }
        if (table.treatedType() != null) {
            throw new ModelQueryConfigurationException(MqCode.MQ4016, owner + ".typeOf(" + table.describe()
                    + "): a type read over a treated node is not a measured cell (R-COL-36); read the type of the "
                    + "untreated node");
        }
        var siteUses = EnumSet.noneOf(TreatUse.class);
        siteUses.addAll(uses);
        siteUses.add(TreatUse.TYPE);
        TreatUse join = joinUse(table.key());
        if (join != null) {
            siteUses.add(join);
        }
        Class<?> entity = entity(table.key());
        admit(new TreatSite(entity, entity, Optional.empty(), shape, anchored, siteUses),
                "typeOf(" + table.describe() + ")");
    }

    /**
     * How {@code key}'s node is reached: {@code LEFT_JOIN} when any join on its path is LEFT, since the node is then
     * absent on some rows, else {@code INNER_JOIN} below a join, else {@code null} for the root or a treat of it.
     */
    static TreatUse joinUse(JoinKey key) {
        TreatUse use = null;
        for (JoinKey node = key; node != null && node.parent() != null; node = node.parent()) {
            if (node.type() == JoinType.LEFT) {
                return TreatUse.LEFT_JOIN;
            }
            if (node.type() != null) {
                use = TreatUse.INNER_JOIN;
            }
        }
        return use;
    }

    /**
     * {@code MQ1106} for an {@code ofType} or {@code notOfType} type the metamodel does not map as an entity, or that
     * is not its node's entity or a subtype of it, before any statement (R-FLT-28); the build checks it again.
     */
    private void checkTypes(Condition condition) {
        if (metamodel == null || renderer == null || detecting
                || !(condition.column().orElse(null) instanceof ExpressionField<?, ?> typeOf)) {
            return;
        }
        for (TableField<?, ?> table : ExpressionField.typeReads(typeOf)) {
            Class<?> node = entity(table.key());
            for (Object value : condition.values()) {
                Class<?> type = (Class<?>) value;
                boolean mapped = metamodel.getEntities().stream().anyMatch(each -> each.getJavaType() == type);
                if (!mapped || !node.isAssignableFrom(type)) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1106, owner + ": "
                            + (condition.kind() == Condition.Kind.OF_TYPE ? "ofType" : "notOfType") + "("
                            + table.describe() + "): " + type.getName() + (mapped ? " is not " + node.getSimpleName()
                                    + " or a subtype of it" : " is not a mapped entity"));
                }
            }
        }
    }

    /**
     * What M28 refuses whatever the renderer says (R-COL-35, R-VND-26): a site in a fetch plan's child statement, a
     * join plan or an enricher, which no measured cell covers; a bulk write's, whose translation fails or whose
     * mutation preflight is unmeasured, and a returning model's, whose RETURNING read over a subtype is unmeasured;
     * and a group key or an aggregate argument, which a provider narrows to the subtype (Q-27), except a correlated
     * count's column over its own treated root (R-COL-31).
     */
    private Optional<String> engineRefusal(TreatSite site) {
        if (site.shape() == StatementShape.CHILD) {
            return Optional.of("a treated node in a fetch plan's child statement, join plan or enricher is not "
                    + "supported");
        }
        if (site.shape() == StatementShape.WRITE) {
            return Optional.of("a treated node in a bulk write or a returning model is not supported");
        }
        boolean type = site.uses().contains(TreatUse.TYPE);
        if (site.uses().contains(TreatUse.GROUP_BY) && !type
                || site.uses().contains(TreatUse.AGGREGATE) && !correlatedCount) {
            return Optional.of("a treated group key or aggregate argument is not supported (Q-27)");
        }
        if (type && site.uses().contains(TreatUse.LEFT_JOIN) && !site.uses().contains(TreatUse.PREDICATE)
                && !site.uses().contains(TreatUse.NULL_TEST)) {
            return Optional.of("typeOf over a LEFT-joined node reads no type on a row whose join is absent, and the "
                    + "provider fails to resolve it rather than reading NULL (R-COL-36); filter it with notOfType");
        }
        return Optional.empty();
    }

    private Class<?> entity(JoinKey key) {
        if (key.parent() == null || key.type() == null) {
            if (metamodel != null) {
                return metamodel.getEntities().stream().map(type -> (Class<?>) type.getJavaType())
                        .filter(type -> type.getName().equals(key.attribute())).findFirst().orElseThrow(() ->
                                new ModelQueryDefinitionException(MqCode.MQ1106,
                                        owner + ": unmapped treated entity " + key.attribute()));
            }
            Class<?> known = entities.get(key.attribute());
            if (known != null) {
                return known;
            }
            // A check without a metamodel names a node first met under an aggregate, which carries no table.
            try {
                return Class.forName(key.attribute(), false, TreatChecks.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new ModelQueryDefinitionException(MqCode.MQ1106, owner + ": unknown entity " + key.attribute());
            }
        }
        var attribute = metamodel.managedType(entity(key.parent())).getAttribute(key.attribute());
        return attribute instanceof PluralAttribute<?, ?, ?> plural ? plural.getElementType().getJavaType()
                : ((SingularAttribute<?, ?>) attribute).getType().getJavaType();
    }

    private String strategy(Class<?> entity) {
        for (Class<?> type = entity; type != null; type = type.getSuperclass()) {
            var inheritance = type.getDeclaredAnnotation(jakarta.persistence.Inheritance.class);
            if (inheritance != null) {
                return inheritance.strategy().name();
            }
        }
        return "SINGLE_TABLE (default or XML mapping)";
    }

    void condition(Condition condition) {
        switch (condition.kind()) {
            case OR -> using(TreatUse.DISJUNCTION, () -> condition.children().forEach(this::condition));
            case NOT -> using(TreatUse.NEGATED, () -> condition.children().forEach(this::condition));
            case AND -> condition.children().forEach(this::condition);
            default -> {
                Runnable scan = () -> {
                    condition.column().ifPresent(this::field);
                    condition.right().ifPresent(this::field);
                    condition.operands().forEach(this::field);
                    condition.path().ifPresent(path -> {
                        register(path);
                        using(TreatUse.SUBQUERY, () -> table(path.key()));
                    });
                    if (condition.subSelect().isPresent() || condition.path().isPresent()) {
                        using(TreatUse.SUBQUERY, () -> condition.children().forEach(this::condition));
                    } else {
                        condition.children().forEach(this::condition);
                    }
                    condition.subSelect().ifPresent(this::subSelect);
                };
                Condition.Kind kind = condition.kind();
                if (kind == Condition.Kind.OF_TYPE || kind == Condition.Kind.NOT_OF_TYPE) {
                    typeCondition(condition, scan);
                } else if (NULL_ACCEPTING.contains(kind)) {
                    // Rendered as "<test> OR column IS NULL" (R-FLT-04): the site is the spike's ne/notIn envelope.
                    using(TreatUse.PREDICATE, () -> using(TreatUse.NULL_TEST, () -> using(TreatUse.DISJUNCTION, scan)));
                } else {
                    using(kind == Condition.Kind.IS_NULL || kind == Condition.Kind.IS_NOT_NULL ? TreatUse.NULL_TEST
                            : TreatUse.PREDICATE, scan);
                }
            }
        }
    }

    /**
     * An {@code ofType} or {@code notOfType}: a {@code TYPE} predicate, none for an empty collection, which reads no
     * type. A {@code notOfType} over a LEFT-joined node is the spike's {@code NOT IN ... OR IS NULL} envelope
     * (R-FLT-28).
     */
    private void typeCondition(Condition condition, Runnable scan) {
        if (condition.values().isEmpty()) {
            return;
        }
        Runnable checked = () -> {
            scan.run();
            checkTypes(condition);
        };
        boolean left = condition.column().orElse(null) instanceof ExpressionField<?, ?> typeOf
                && ExpressionField.typeReads(typeOf).stream().anyMatch(table ->
                        joinUse(table.key()) == TreatUse.LEFT_JOIN);
        if (condition.kind() == Condition.Kind.NOT_OF_TYPE && left) {
            using(TreatUse.PREDICATE, () -> using(TreatUse.NEGATED, () -> using(TreatUse.NULL_TEST,
                    () -> using(TreatUse.DISJUNCTION, checked))));
        } else {
            using(TreatUse.PREDICATE, checked);
        }
    }

    void subSelect(SubSelect<?, ?> sub) {
        unanchored(() -> using(TreatUse.SUBQUERY, () -> {
            field(sub.column());
            sub.conditions().forEach(this::condition);
        }));
    }

    /** Scans a sub-select, whose own root no anchor covers, with {@code anchored} false (R-VND-26). */
    void unanchored(Runnable scan) {
        boolean previous = anchored;
        anchored = false;
        try {
            scan.run();
        } finally {
            anchored = previous;
        }
    }

    void fetch(FetchPlan<?> plan) {
        if (plan == null || !seen.add(plan)) {
            return;
        }
        statement(StatementShape.CHILD, false, () -> {
            plan.needed().forEach(this::field);
            plan.childLoads().forEach(child -> {
                child.field().through().ifPresent(path -> {
                    register(path);
                    table(path.key());
                });
                definition(child.query());
            });
            plan.joinPlans().forEach(join -> {
                join.plan().selection().fields().forEach(this::field);
                fetch(join.plan());
            });
        });
    }

    void source(SourceQuery<?> source) {
        if (source != null && !detecting && seen.add(source)) {
            if (metamodel != null && source.anchors() && source.treated()) {
                // An anchored body selects its single root id, as a content statement does (R-SRC-20, R-COL-35).
                rootId(metamodel.entity(source.root().rootEntity()), owner);
            }
            source.checkTreats(this);
        }
    }

    /**
     * {@code MQ4016} for a {@code union}, {@code intersect} or {@code except} branch with a treated node that selects
     * no untreated root id: the branch needs the hidden anchor, which would change the rows the operation compares
     * and the branches' arity (R-COL-35). A {@code unionAll} keeps every row, so it is unaffected.
     */
    void setOperationBranch(SetOperation operation, SourceQuery<?> branch) {
        int before = occurrences;
        source(branch);
        if (operation == SetOperation.UNION_ALL || occurrences == before) {
            return;
        }
        Class<?> entity = branch.root() == null ? null : branch.root().rootEntity();
        String id = metamodel == null || entity == null ? null : rootId(metamodel.entity(entity), owner);
        if (id == null || !selectsRootId(branch.fields().fields(), id)) {
            throw new ModelQueryConfigurationException(MqCode.MQ4016, owner + ": a " + operation + " branch over "
                    + (entity == null ? "a source" : entity.getSimpleName()) + " has a treated node and selects no "
                    + "untreated root id, so the hidden id anchor would change the rows it compares; select the root "
                    + "id in the branch, or use unionAll");
        }
    }

    /**
     * Refuses a combination whose treated branch needs the hidden root-id anchor that every branch then selects
     * (R-SRC-20): while another branch can't select it, being grouped, recursive or over another source, as the
     * branches would differ in arity; while any operation in it, nested ones included, is not a {@code unionAll}, as
     * the anchor would change the rows that operation compares; and while the branches' root ids differ in Java type,
     * as the anchor column would have no one type.
     */
    void combinedAnchor(SourceQuery<?> combined) {
        if (detecting || !combined.rootIdAnchored(Optional.ofNullable(metamodel))) {
            return;
        }
        SourceQuery<?> branch = combined.unanchoredBranch();
        if (branch != null) {
            throw new ModelQueryConfigurationException(MqCode.MQ4016, owner + ": a set operation's "
                    + (branch.groupKeys() != null ? "grouped" : branch.recursive() ? "recursive" : "source-over-source")
                    + " branch can't select the hidden id anchor another branch's treated node needs, so the branches "
                    + "would differ in arity; select the untreated root id in the treated branch");
        }
        SetOperation operation = combined.distinctOperation();
        if (operation != null) {
            throw new ModelQueryConfigurationException(MqCode.MQ4016, owner + ": a treated branch needs the hidden id "
                    + "anchor every branch of the combination selects, which would change the rows its " + operation
                    + " compares; select the untreated root id in the treated branch, or use unionAll throughout");
        }
        if (metamodel == null) {
            return;
        }
        List<SourceQuery<?>> branches = new ArrayList<>();
        combined.plainBranches(branches);
        Class<?> idType = null;
        for (SourceQuery<?> plain : branches) {
            Class<?> type = ColumnField.boxed(
                    rootIdAttribute(metamodel.entity(plain.root().rootEntity()), owner).getJavaType());
            if (idType != null && !idType.equals(type)) {
                throw new ModelQueryConfigurationException(MqCode.MQ4016, owner + ": a treated branch needs the "
                        + "hidden id anchor every branch of the combination selects, and the branches' root ids "
                        + "differ in type (" + idType.getSimpleName() + ", " + type.getSimpleName() + "); select the "
                        + "untreated root id in the treated branch");
            }
            idType = type;
        }
    }

    void assignment(Assignment<?, ?> assignment) {
        field(assignment.column());
        if (assignment instanceof Assignment.Computed<?, ?> computed) {
            field(computed.value());
        }
    }
}
