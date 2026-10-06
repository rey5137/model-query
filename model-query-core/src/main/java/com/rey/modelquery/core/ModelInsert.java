package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * An immutable bulk-insert definition: an insert-select, the rows a filter over a source model matches, or an
 * insert-values with a conflict clause. An insert-values without one is a {@link ValuesInsert}, whose keys can be
 * returned. Like a {@link ModelQuery} it holds no Criteria object, and the rows it was given were read once at
 * {@code build()}, so one instance can be shared by any number of threads (INV-9).
 *
 * <p>A bulk insert loads no entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit; the
 * executor's {@code persist} is the write that runs them (R-WRT-24).
 *
 * @param <E> the root entity the rows are written to
 * @param <M> the insert model
 * @implSpec R-WRT-24, R-WRT-26, R-WRT-27, R-WRT-28, R-WRT-34, R-WRT-37, D-60, D-61, D-116, D-117
 */
@Incubating
public sealed class ModelInsert<E, M> permits ValuesInsert {

    /** The prefix of the parameter names a source select binds its {@code set} constants to. */
    private static final String CONSTANT = "mqConstant";

    private final InsertDraft<E, M> definition;

    ModelInsert(InsertDraft<E, M> definition) {
        this.definition = definition;
    }

    /**
     * Starts an insert-select of {@code columns}' rows from {@code source}, the source query model's root: a join
     * {@code TableField} does not compile. Each stage returns a new immutable builder; the order is the mappings and
     * constants, one row choice, then options (D-60).
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code source} is a join of a root's own type
     */
    public static <M, E, S> SelectStart<E, M> select(InsertColumns<M, E> columns, TableField<S, S> source) {
        return new SelectStart<>(InsertDraft.select(Objects.requireNonNull(columns, "columns"),
                InsertRules.requireRoot(source, "insertFrom")));
    }

    /** The entity the rows are written to. */
    public Class<E> rootEntity() {
        return definition.columns().rootEntity();
    }

    /** The insert's {@code chunked(...)} options, or empty without them (R-WRT-28, R-WRT-29). */
    public Optional<ChunkOptions> chunkOptions() {
        return Optional.ofNullable(definition.chunkOptions());
    }

    /** The insert's own {@code persistenceContext(...)}, which wins over the executor's configured mode (D-62). */
    public Optional<PersistenceContextMode> persistenceContext() {
        return Optional.ofNullable(definition.persistenceContext());
    }

    /** The entity an insert-select reads its rows from, or empty for an insert-values. */
    @EngineFacing
    public Optional<Class<?>> sourceEntity() {
        TableField<?, ?> source = definition.source();
        return source == null ? Optional.empty() : Optional.of(source.rootEntity());
    }

    /**
     * Checks the definition against {@code metamodel}, which {@code build()} cannot see (INV-7). An executor calls it
     * on the definition's first execution per {@code EntityManagerFactory}, before any statement (D-61).
     *
     * @throws ModelQueryDefinitionException {@code MQ1802} when the columns and constants write part of the root's
     *     id; {@code MQ1801} for an insert-select {@code map} or {@code where} reading a column that is not on the
     *     source root, or a {@code map} between attributes of different types; {@code MQ1804} for a conflict update
     *     assigning an id or the {@code @Version} attribute, and {@code MQ1606} for a {@code @Version} of a type it
     *     cannot increment, unless {@code keepVersion()} (R-WRT-26, R-WRT-27, R-WRT-34)
     */
    @EngineFacing
    public void checkMetamodel(Metamodel metamodel) {
        EntityType<E> entity = metamodel.entity(rootEntity());
        InsertMetamodel.writesId(entity, written(), toString());
        sourceEntity().ifPresent(source -> {
            InsertMetamodel.checkMappings(metamodel, entity, source, definition.mappings());
            InsertMetamodel.checkWhere(source, ConditionGroup.conditions(definition.where().where()));
        });
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        if (conflict != null && conflict.update() != null) {
            checkConflictUpdate(entity, conflict);
        }
    }

    /**
     * Checks a conflict update against the root's id and {@code @Version}, which {@code build()} cannot see.
     *
     * @throws ModelQueryDefinitionException {@code MQ1804} or {@code MQ1606}, as {@link #checkMetamodel} states
     */
    private void checkConflictUpdate(EntityType<E> entity, InsertDraft.Conflict<E, M> conflict) {
        Set<String> ids = WriteRendering.idNames(entity);
        Optional<String> version = WriteRendering.version(entity).map(Attribute::getName);
        var written = new ArrayList<ColumnField<?, ?, ?>>(conflict.update().fromRow());
        conflict.update().assignments().forEach(assignment -> written.add(assignment.column()));
        for (ColumnField<?, ?, ?> column : written) {
            String name = column.name();
            String head = name.indexOf('.') < 0 ? name : name.substring(0, name.indexOf('.'));
            if (ids.contains(head) || version.filter(head::equals).isPresent()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1804, column + ": doUpdate(...) assigns "
                        + entity.getJavaType().getSimpleName() + "." + head + ", "
                        + (ids.contains(head) ? "an id attribute, which stays as stored"
                                : "the @Version attribute, which the update increments unless keepVersion()"));
            }
        }
        if (!conflict.keepVersion()) {
            WriteRendering.version(entity).ifPresent(attribute -> {
                Class<?> type = ColumnField.boxed(attribute.getJavaType());
                if (!ModelUpdate.VERSION_TYPES.contains(type)) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1606, this + ": "
                            + entity.getJavaType().getSimpleName() + "." + attribute.getName() + " is a "
                            + type.getSimpleName() + " @Version, which a conflict update cannot increment; "
                            + "keepVersion() leaves it alone");
                }
            });
        }
    }

    /**
     * Whether the columns and constants write the root's whole id, which the executor holds against the root's
     * generator (R-WRT-26).
     *
     * @throws ModelQueryDefinitionException {@code MQ1802} when they write part of it, as {@link #checkMetamodel}
     */
    @EngineFacing
    public boolean writesId(Metamodel metamodel) {
        return InsertMetamodel.writesId(metamodel.entity(rootEntity()), written(), toString());
    }

    /**
     * The attributes an insert-select writes, in the order {@link #buildSelect} selects their values: each column of
     * the model's {@code InsertColumns}, then each {@code set} constant. A to-one attribute, which a column writes by
     * id, is named with its target's id attribute below it ({@code customer.id}): the select copies an id, never an
     * entity (R-WRT-27).
     *
     * @throws IllegalStateException for an insert-values
     */
    @EngineFacing
    public List<String> selectedAttributes(Metamodel metamodel) {
        requireSource();
        return writtenPaths(metamodel);
    }

    /**
     * The attributes an insert-values writes, in the order of each row of {@link #valueRows}: each column of the
     * model's {@code InsertColumns}, then each {@code set} constant. A to-one attribute is named with its target's id
     * attribute below it ({@code customer.id}), so a row writes the id, as an insert-select does (R-WRT-30).
     *
     * @throws IllegalStateException for an insert-select
     */
    @EngineFacing
    public List<String> valueAttributes(Metamodel metamodel) {
        requireValues();
        return writtenPaths(metamodel);
    }

    /**
     * The number of rows an insert-values writes, read at {@code build()} (R-WRT-29).
     *
     * @throws IllegalStateException for an insert-select
     */
    @EngineFacing
    public int rowCount() {
        return requireValues().size();
    }

    /**
     * Rows {@code from} (inclusive) to {@code to} (exclusive) of an insert-values, each a new list of attribute values
     * in the order of {@link #valueAttributes}: each model value passed through its column's converter, a
     * {@code null} kept, then each {@code set} constant's value likewise (R-WRT-25, R-WRT-30). The rows
     * {@code build()} read are not changed.
     *
     * @throws IllegalStateException for an insert-select
     * @throws IndexOutOfBoundsException for a range outside {@code 0} to {@link #rowCount()}
     */
    @EngineFacing
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<List<Object>> valueRows(int from, int to) {
        List<List<Object>> read = requireValues().subList(from, to);
        List<ColumnField<M, E, ?>> columns = definition.columns().columns();
        var constants = new ArrayList<Object>();
        for (Assignment<?, ?> constant : definition.constants()) {
            Assignment.Value<?, ?> value = (Assignment.Value<?, ?>) constant;
            constants.add(((ColumnField) value.column()).toAttribute(value.value()));
        }
        var rows = new ArrayList<List<Object>>(read.size());
        for (List<Object> row : read) {
            var values = new ArrayList<>(columns.size() + constants.size());
            for (int c = 0; c < columns.size(); c++) {
                Object value = row.get(c);
                values.add(value == null ? null : ((ColumnField) columns.get(c)).toAttribute(value));
            }
            values.addAll(constants);
            rows.add(values);
        }
        return rows;
    }

    /**
     * The source root's id as a key of plain attribute columns, which a chunked insert-select pages over: the
     * {@code @Id}, the components of an {@code @EmbeddedId}, or the {@code @IdClass} attributes by name (R-WRT-28).
     *
     * @throws IllegalStateException for an insert-values
     */
    @EngineFacing
    public PrimaryKey<Object, ?> sourceKey(Metamodel metamodel) {
        return InsertMetamodel.idKey(metamodel.entity(requireSource().rootEntity()), requireSource());
    }

    /**
     * Renders the source select of an insert-select: from the source root, each {@code map}'s source column in the
     * order of {@link #selectedAttributes}, then each {@code set} constant as a named parameter
     * ({@link #selectParameters}), with the {@code where} rendered as a read renders it, joins included. Duplicates
     * from a to-many join stay: the insert writes the rows the equivalent read returns (R-WRT-27, R-WRT-28). The
     * returned query maps no model.
     *
     * @throws IllegalStateException for an insert-values
     */
    @EngineFacing
    public BuiltQuery<M> buildSelect(CriteriaBuilder cb, RenderOptions options) {
        return select(cb, options, null, null);
    }

    /**
     * As {@link #buildSelect(CriteriaBuilder, RenderOptions)}, narrowed to the source rows whose {@code key}, the
     * {@link #sourceKey}, is one of {@code keys}: one chunk of a chunked insert-select (R-WRT-28).
     *
     * @throws IllegalArgumentException for empty {@code keys}, which would leave the rows unchosen
     */
    @EngineFacing
    public BuiltQuery<M> buildSelect(CriteriaBuilder cb, RenderOptions options, PrimaryKey<Object, ?> key,
            List<?> keys) {
        return select(cb, options, Objects.requireNonNull(key, "key"), WriteRendering.selectedKeys(keys));
    }

    /**
     * Renders the key select of a chunked insert-select: the {@code key} columns, the {@link #sourceKey}, of the
     * source rows the {@code where} chooses, rendered as a read renders it, with no order. An executor adds the keyset
     * order, the cursor, the row limit and any lock (R-WRT-17, R-WRT-28).
     */
    @EngineFacing
    public BuiltQuery<Object> buildKeySelect(CriteriaBuilder cb, RenderOptions options, PrimaryKey<Object, ?> key) {
        Class<?> source = requireSource().rootEntity();
        return WriteRendering.keySelect(null, definition.where().where(), Objects.requireNonNull(key, "key"), source,
                cb, options, toString());
    }

    /**
     * The values of the {@code set} constants, by the name of the parameter {@link #buildSelect} renders each as:
     * each value passed through its column's converter, a to-one's the target id, in the order of the attributes they
     * are written to. The executor hands them to the provider's insert, which binds each as its attribute's type
     * (R-WRT-14, R-WRT-25, R-WRT-30).
     *
     * @throws IllegalStateException for an insert-values
     */
    @EngineFacing
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String, Object> selectParameters() {
        requireSource();
        var parameters = new LinkedHashMap<String, Object>();
        List<Assignment<?, ?>> constants = definition.constants();
        for (int c = 0; c < constants.size(); c++) {
            Assignment.Value<?, ?> constant = (Assignment.Value<?, ?>) constants.get(c);
            parameters.put(CONSTANT + c, ((ColumnField) constant.column()).toAttribute(constant.value()));
        }
        return Collections.unmodifiableMap(parameters);
    }

    /**
     * The attributes the conflict clause detects a conflict on, as its columns name them, in the order named; empty
     * without a clause (R-WRT-34).
     */
    @EngineFacing
    public List<String> conflictKeys() {
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        return conflict == null ? List.of() : conflict.keys().stream().map(ColumnField::name).toList();
    }

    /** Whether the conflict clause skips a conflicting row, {@code doNothing()}; false without a clause. */
    @EngineFacing
    public boolean conflictSkips() {
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        return conflict != null && conflict.update() == null;
    }

    /** Whether the conflict clause accepts the vendor's any-unique-key detection, {@code anyUniqueKey()} (R-WRT-36). */
    @EngineFacing
    public boolean conflictAnyUniqueKey() {
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        return conflict != null && conflict.anyUniqueKey();
    }

    /**
     * Renders the conflict update of a {@code doUpdate} clause: each {@code setFromRow} column as {@code excluded}'s
     * attribute, each {@code set} as a {@code value} the provider binds as the attribute's type and each
     * {@code setNull} as a {@code null} value bound likewise, in the order assigned, and the root's {@code @Version}
     * increment unless {@code keepVersion()}; an assignment to a column the {@code where} reads comes after the
     * others, since MySQL renders the {@code where} into each assignment and reads the columns earlier assignments
     * wrote. A to-one attribute is written by its target's id ({@code customer.id}), as the rows are. Returns the
     * {@code where} over the stored row, {@code target}, or empty without one or when every filter was skipped
     * (R-WRT-13, R-WRT-34, R-WRT-35).
     *
     * @throws IllegalStateException without a {@code doUpdate} clause
     */
    @EngineFacing
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<Predicate> buildConflictUpdate(Root<E> target, Root<E> excluded, CriteriaBuilder cb,
            RenderOptions options, BiConsumer<Path<?>, Object> value, BiConsumer<Path<?>, Expression<?>> expression) {
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        if (conflict == null || conflict.update() == null) {
            throw new IllegalStateException(this + ": no doUpdate(...) clause to render");
        }
        EntityType<E> entity = target.getModel();
        Set<String> read = conflictWhereReads(conflict);
        var first = new ArrayList<Runnable>();
        var last = new ArrayList<Runnable>();
        for (ColumnField<M, E, ?> column : conflict.update().fromRow()) {
            String path = InsertMetamodel.toOneIdPath(entity, column.name());
            (read.contains(column.name()) ? last : first).add(() -> expression.accept(path(target, path),
                    path(excluded, path)));
        }
        for (Assignment<?, ?> assignment : conflict.update().assignments()) {
            ColumnField column = assignment.column();
            Path<?> path = path(target, InsertMetamodel.toOneIdPath(entity, column.name()));
            // A setNull binds a null as the attribute's type: a NULL literal typed by its Java class fails for a
            // converted class, which the provider does not know as a type.
            Object bound = assignment instanceof Assignment.Value<?, ?> assigned ? column.toAttribute(assigned.value())
                    : null;
            (read.contains(column.name()) ? last : first).add(() -> value.accept(path, bound));
        }
        if (!conflict.keepVersion()) {
            WriteRendering.version(entity).ifPresent(version -> {
                Path<?> path = target.get(version.getName());
                Object next = ModelUpdate.nextVersion(path, cb);
                (read.contains(version.getName()) ? last : first).add(next instanceof Expression<?> increment
                        ? () -> expression.accept(path, increment) : () -> value.accept(path, next));
            });
        }
        first.forEach(Runnable::run);
        last.forEach(Runnable::run);
        List<Predicate> where = ConditionGroup.toPredicates(conflict.update().where(),
                JoinContext.of(target, cb, null, options));
        return where.isEmpty() ? Optional.empty() : Optional.of(ConditionGroup.and(cb, where));
    }

    /**
     * The columns a {@code doUpdate} clause's {@code where} reads that the update also assigns, the root's
     * {@code @Version} included unless {@code keepVersion()}, in the order assigned; empty for {@code doNothing()},
     * without a clause or without a {@code where}. Two or more are refused unless the executor's configuration allows
     * them on a vendor whose {@code where} reads the stored row (R-WRT-34, D-117).
     */
    @EngineFacing
    public List<String> conflictWhereReadsAssigned(Metamodel metamodel) {
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        if (conflict == null || conflict.update() == null) {
            return List.of();
        }
        Set<String> read = conflictWhereReads(conflict);
        var assigned = new ArrayList<String>();
        conflict.update().fromRow().forEach(column -> assigned.add(column.name()));
        conflict.update().assignments().forEach(assignment -> assigned.add(assignment.column().name()));
        if (!conflict.keepVersion()) {
            WriteRendering.version(metamodel.entity(rootEntity()))
                    .ifPresent(version -> assigned.add(version.getName()));
        }
        return assigned.stream().filter(read::contains).toList();
    }

    /** The names of the root columns a conflict update's {@code where} reads. */
    private static Set<String> conflictWhereReads(InsertDraft.Conflict<?, ?> conflict) {
        Set<String> read = new HashSet<>();
        conflictWhereColumns(ConditionGroup.conditions(conflict.update().where()), read);
        return read;
    }

    /** Adds the names of the root columns {@code conditions} read, through expressions and groups, to {@code read}. */
    private static void conflictWhereColumns(List<Condition> conditions, Set<String> read) {
        for (Condition condition : conditions) {
            condition.column().ifPresent(field -> whereColumn(field, read));
            condition.right().ifPresent(field -> whereColumn(field, read));
            conflictWhereColumns(condition.children(), read);
        }
    }

    private static void whereColumn(SelectField<?, ?> field, Set<String> read) {
        if (field instanceof ExpressionField<?, ?> expression) {
            expression.columns().forEach(column -> read.add(column.name()));
        } else if (field instanceof ColumnField<?, ?, ?> column) {
            read.add(column.name());
        }
    }

    /**
     * The most binds the conflict clause adds to a statement, besides its rows: the {@code where}'s values once per
     * assignment and once more, since MySQL renders it as a {@code CASE} in each assignment, plus each {@code set} or
     * {@code setNull} value and the version increment; 0 for {@code doNothing()} or without a clause (R-WRT-29,
     * D-80).
     */
    @EngineFacing
    public int conflictBinds() {
        return conflictWhereBinds() * (conflictAssignments() + 1) + conflictValueBinds();
    }

    /**
     * The binds of {@link #conflictBinds} that a statement's reported parameters leave out: the {@code where}'s values
     * once per assignment, which MySQL's {@code CASE} per assignment repeats (R-WRT-35, D-80).
     */
    @EngineFacing
    public int conflictRepeatedBinds() {
        return conflictWhereBinds() * conflictAssignments();
    }

    private int conflictWhereBinds() {
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        if (conflict == null || conflict.update() == null) {
            return 0;
        }
        int binds = 0;
        for (Condition condition : ConditionGroup.conditions(conflict.update().where())) {
            binds += ExpressionField.conditionBinds(condition);
        }
        return binds;
    }

    /** The conflict update's assignments, the version increment counted unless {@code keepVersion()}. */
    private int conflictAssignments() {
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        if (conflict == null || conflict.update() == null) {
            return 0;
        }
        return conflict.update().fromRow().size() + conflict.update().assignments().size()
                + (conflict.keepVersion() ? 0 : 1);
    }

    /**
     * The conflict update's {@code set} and {@code setNull} values and the version increment's, counted unless
     * {@code keepVersion()}.
     */
    private int conflictValueBinds() {
        InsertDraft.Conflict<E, M> conflict = definition.conflict();
        if (conflict == null || conflict.update() == null) {
            return 0;
        }
        return conflict.update().assignments().size() + (conflict.keepVersion() ? 0 : 1);
    }

    /** The path of {@code attribute}, dotted, below {@code root}. */
    private static Path<?> path(Root<?> root, String attribute) {
        Path<?> path = root;
        for (String segment : attribute.split("\\.")) {
            path = path.get(segment);
        }
        return path;
    }

    private BuiltQuery<M> select(CriteriaBuilder cb, RenderOptions options, PrimaryKey<Object, ?> key,
            List<Object> keys) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<?> from = query.from(requireSource().rootEntity());
        JoinContext joins = JoinContext.of(from, cb, query, options);
        // build() checked that each column is mapped exactly once, so every slot is filled.
        var sources = new ArrayList<ColumnField<?, ?, ?>>(Collections.nCopies(definition.columns().columns().size(),
                null));
        definition.mappings().forEach(mapping -> sources.set(definition.columns().indexOf(mapping.target()),
                mapping.source()));
        var selected = new ArrayList<Selection<?>>();
        sources.forEach(source -> selected.add(source.path(joins)));
        // A parameter, not cb.literal, which a provider may inline: a value is always bound (R-WRT-14).
        List<Assignment<?, ?>> constants = definition.constants();
        for (int c = 0; c < constants.size(); c++) {
            selected.add(cb.parameter(constants.get(c).column().attributeType(), CONSTANT + c));
        }
        query.multiselect(selected);
        var predicates = new ArrayList<Predicate>();
        if (keys != null) {
            predicates.add(key.in(keys, joins, cb, true));
        }
        predicates.addAll(ConditionGroup.toPredicates(definition.where().where(), joins));
        if (!predicates.isEmpty()) {
            query.where(predicates.toArray(Predicate[]::new));
        }
        String label = toString();
        return new BuiltQuery<>(query, joins, RowSelection.of(sources), row -> {
            throw new UnsupportedOperationException(label + ": an insert-select's source select maps no model");
        });
    }

    private TableField<?, ?> requireSource() {
        TableField<?, ?> source = definition.source();
        if (source == null) {
            throw new IllegalStateException(this + ": an insert-values has no source select");
        }
        return source;
    }

    /** The rows {@code build()} read, else {@link IllegalStateException} for an insert-select. */
    private List<List<Object>> requireValues() {
        if (definition.source() != null) {
            throw new IllegalStateException(this + ": an insert-select has no rows of values");
        }
        return definition.values();
    }

    /** The {@link #written} attributes, a to-one's with its target's id attribute appended. */
    private List<String> writtenPaths(Metamodel metamodel) {
        EntityType<E> entity = metamodel.entity(rootEntity());
        var names = new ArrayList<String>();
        for (String name : written()) {
            names.add(InsertMetamodel.toOneIdPath(entity, name));
        }
        return names;
    }

    /** The attributes the insert writes: its columns, then its {@code set} constants. */
    private List<String> written() {
        var names = new ArrayList<String>();
        definition.columns().columns().forEach(column -> names.add(column.name()));
        definition.constants().forEach(constant -> names.add(constant.column().name()));
        return names;
    }

    /**
     * The entity and how many rows, or where they come from, for the executor's log: never a value or a filter's
     * values (D-95, R-INS-05). The format is not API.
     */
    @Override
    public String toString() {
        return definition.toString();
    }

    InsertDraft<E, M> definition() {
        return definition;
    }

    /**
     * The first stage of an insert-select, before its first {@code map}.
     *
     * @param <E> the root entity
     * @param <M> the insert model
     */
    @Incubating
    public static final class SelectStart<E, M> {

        private final InsertDraft<E, M> draft;

        private SelectStart(InsertDraft<E, M> draft) {
            this.draft = draft;
        }

        /**
         * Copies {@code source}, a column of the source query model {@code Q}, into {@code target}. This first call
         * fixes {@code Q}, so a later {@code map} from another model, an aggregate or a grouped source does not
         * compile. Both columns need the same converter class or none, checked at {@code build()}, and equal entity
         * attribute types, checked on first execution: either failure is {@code MQ1801}, since the database copies
         * attribute values (R-WRT-27).
         */
        public <Q, C> Mapping<E, M, Q> map(ColumnField<M, E, C> target, ColumnField<Q, ?, C> source) {
            return new Mapping<>(draft.map(Objects.requireNonNull(target, "target"),
                    Objects.requireNonNull(source, "source")));
        }
    }

    /**
     * The mapping stage of an insert-select: more {@code map}s and {@code set} constants, then exactly one row
     * choice (R-WRT-12, R-WRT-27).
     *
     * @param <E> the root entity
     * @param <M> the insert model
     * @param <Q> the source query model
     */
    @Incubating
    public static final class Mapping<E, M, Q> {

        private final InsertDraft<E, M> draft;

        private Mapping(InsertDraft<E, M> draft) {
            this.draft = draft;
        }

        /** Copies {@code source} into {@code target}, as {@link SelectStart#map} does. */
        public <C> Mapping<E, M, Q> map(ColumnField<M, E, C> target, ColumnField<Q, ?, C> source) {
            return new Mapping<>(draft.map(Objects.requireNonNull(target, "target"),
                    Objects.requireNonNull(source, "source")));
        }

        /**
         * Writes {@code value} to {@code column} in every row, one bind per row: a column the server fills, which the
         * insert model leaves out. A column the model has, or one set twice, throws {@code MQ1801} at
         * {@code build()} (R-WRT-25).
         *
         * @throws ModelQueryDefinitionException {@code MQ1603} for a {@code null} value
         */
        public <C> Mapping<E, M, Q> set(ColumnField<?, E, C> column, C value) {
            return new Mapping<>(draft.set(Assignment.of(Objects.requireNonNull(column, "column"), value)));
        }

        /**
         * Copies the source rows {@code filters} matches: it receives an empty {@link Filters}, and every filter it
         * adds is ANDed. It runs once, here, as {@link ModelQuery.Builder#where} does. If every filter is skipped,
         * {@link SelectOptions#build()} throws {@code MQ1601}; {@link #all()} is the only way to copy every row
         * (R-WRT-12).
         */
        public SelectOptions<E, M> where(UnaryOperator<Filters<Q>> filters) {
            return new SelectOptions<>(draft.where(WriteRows.where(FilterGroup.collect(
                    draft.source().rootEntity(), Objects.requireNonNull(filters, "filters")))));
        }

        /** Copies every source row; no {@code where} follows (R-WRT-12). */
        public SelectOptions<E, M> all() {
            return new SelectOptions<>(draft.where(WriteRows.everyRow()));
        }
    }

    /**
     * The options stage of an insert-select, after the row choice. There is no conflict clause (D-116), no
     * {@code keepVersion} and no {@code startAfter}: a resumed copy narrows its {@code where} on the source key.
     *
     * @param <E> the root entity
     * @param <M> the insert model
     */
    @Incubating
    public static final class SelectOptions<E, M> {

        private final InsertDraft<E, M> draft;

        private SelectOptions(InsertDraft<E, M> draft) {
            this.draft = draft;
        }

        /**
         * Copies in chunks, as {@code options} states, key-first over the distinct source-root ids, so each source
         * row is read once. A source and target that overlap, or a source that joins through a collection table
         * ({@code @ManyToMany}, {@code @ElementCollection}), throw {@code MQ1806} on first execution. A
         * {@code notExists} guard correlated on a value several source rows share writes fewer rows chunked than
         * unchunked, since a later chunk skips rows an earlier one wrote (R-WRT-28).
         */
        public SelectOptions<E, M> chunked(ChunkOptions options) {
            return new SelectOptions<>(draft.chunk(Objects.requireNonNull(options, "options")));
        }

        /** What the insert does to the persistence context afterwards, over the executor's configured mode (D-62). */
        public SelectOptions<E, M> persistenceContext(PersistenceContextMode mode) {
            return new SelectOptions<>(draft.mode(Objects.requireNonNull(mode, "mode")));
        }

        /**
         * Checks the definition and returns it.
         *
         * @throws ModelQueryDefinitionException {@code MQ1601} when the rows were chosen by a {@code where} whose
         *     every filter was skipped; {@code MQ1801} for a column of the insert model not mapped, mapped twice, or
         *     mapped from a column with another converter class, a {@code map} target not in the model's
         *     {@code InsertColumns}, a {@code set} on a column the model has, a column set twice, or a {@code set}
         *     column not on the root
         */
        public ModelInsert<E, M> build() {
            return new ModelInsert<>(draft.build());
        }
    }

    /**
     * A conflict clause before its action: {@code doNothing()} or {@code doUpdate(...)}, so a clause without an
     * action does not compile (R-WRT-34).
     *
     * @param <E> the root entity
     * @param <M> the insert model
     */
    @Incubating
    public static final class Conflict<E, M> {

        private final InsertDraft<E, M> draft;
        private final List<ColumnField<M, E, ?>> keys;

        Conflict(InsertDraft<E, M> draft, List<ColumnField<M, E, ?>> keys) {
            this.draft = draft;
            this.keys = keys;
        }

        /**
         * Skips a row whose key is already stored. Where the provider does not render it for the dialect (Hibernate 6
         * on a {@code MERGE} vendor), the insert throws {@code MQ1804} on first execution (D-116).
         */
        public ConflictOptions<E, M> doNothing() {
            return new ConflictOptions<>(draft.conflict(new InsertDraft.Conflict<>(keys, null, false, false)));
        }

        /**
         * Updates the stored row a new row conflicts with, as {@code update} states. A root {@code @Version} is
         * incremented unless {@code keepVersion()} (R-WRT-34).
         */
        public Upserting<E, M> doUpdate(Function<ConflictUpdate<E, M>, ConflictUpdate.Action<E, M>> update) {
            ConflictUpdate.Action<E, M> action = Objects.requireNonNull(
                    Objects.requireNonNull(update, "update").apply(new ConflictUpdate<>()), "update's action");
            return new Upserting<>(draft.conflict(new InsertDraft.Conflict<>(keys, action, false, false)));
        }
    }

    /**
     * The options stage of an insert-values with a conflict clause.
     *
     * @param <E> the root entity
     * @param <M> the insert model
     */
    @Incubating
    public static sealed class ConflictOptions<E, M> permits Upserting {

        final InsertDraft<E, M> draft;

        private ConflictOptions(InsertDraft<E, M> draft) {
            this.draft = draft;
        }

        /**
         * Accepts that the vendor detects a conflict on any unique key, not only the one named, where its profile's
         * {@code conflictTargetHonoured()} is false (MySQL and MariaDB); without it the insert throws {@code MQ1804}
         * there on first execution. It accepts the vendor's count with it: on MySQL every conflicting row counts 1
         * when skipped, filtered out or left unchanged and 2 when changed, at Connector/J's default found rows, and
         * the vendor's key collation (R-WRT-35, R-WRT-36).
         */
        public ConflictOptions<E, M> anyUniqueKey() {
            return new ConflictOptions<>(withConflict(draft.conflict().anyKey()));
        }

        /**
         * Writes in chunks of at most {@code options}' size rows, each one statement. {@code lockKeys()} throws
         * {@code MQ1801} at {@link #build()}, since insert-values selects no key (R-WRT-29, R-WRT-32).
         */
        public ConflictOptions<E, M> chunked(ChunkOptions options) {
            return new ConflictOptions<>(draft.chunk(Objects.requireNonNull(options, "options")));
        }

        /** What the insert does to the persistence context afterwards, over the executor's configured mode (D-62). */
        public ConflictOptions<E, M> persistenceContext(PersistenceContextMode mode) {
            return new ConflictOptions<>(draft.mode(Objects.requireNonNull(mode, "mode")));
        }

        /**
         * Checks the definition, reads every row once into the definition, and returns it (INV-9, R-WRT-30).
         *
         * @throws ModelQueryDefinitionException {@code MQ1803} for a {@code null} row, {@code MQ1802} for a row with
         *     a {@code null} assigned id, {@code MQ1808} for two rows sharing a conflict-key tuple; {@code MQ1801}
         *     for {@code lockKeys()}, a {@code set} on a column the model has, a column set twice or a {@code set}
         *     column not on the root; {@code MQ1804} for a conflict column named twice or not in the model's
         *     {@code InsertColumns}, a {@code doUpdate} assigning a key column, a column twice or a column not on the
         *     root, a {@code setFromRow} column not in {@code InsertColumns}, or a {@code where} reading a joined
         *     column or holding {@code exists} or a sub-select
         */
        public ModelInsert<E, M> build() {
            return new ModelInsert<>(draft.build());
        }

        final InsertDraft<E, M> withConflict(InsertDraft.Conflict<E, M> conflict) {
            return draft.conflict(conflict);
        }
    }

    /**
     * The options stage after {@code doUpdate}, which may also keep the stored row's version.
     *
     * @param <E> the root entity
     * @param <M> the insert model
     */
    @Incubating
    public static final class Upserting<E, M> extends ConflictOptions<E, M> {

        private Upserting(InsertDraft<E, M> draft) {
            super(draft);
        }

        /** Leaves the stored row's {@code @Version} attribute as it is, instead of incrementing it (R-WRT-34). */
        public Upserting<E, M> keepVersion() {
            return new Upserting<>(withConflict(draft.conflict().keep()));
        }

        @Override
        public Upserting<E, M> anyUniqueKey() {
            return new Upserting<>(withConflict(draft.conflict().anyKey()));
        }

        @Override
        public Upserting<E, M> chunked(ChunkOptions options) {
            return new Upserting<>(draft.chunk(Objects.requireNonNull(options, "options")));
        }

        @Override
        public Upserting<E, M> persistenceContext(PersistenceContextMode mode) {
            return new Upserting<>(draft.mode(Objects.requireNonNull(mode, "mode")));
        }
    }
}
