package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.annotations.ExcludeFromDefaults;
import com.rey.modelquery.annotations.ExcludeFromInserts;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.rey.modelquery.processor.ModelDefinition.AggregateDefinition;
import com.rey.modelquery.processor.ModelDefinition.FilterColumnDefinition;
import com.rey.modelquery.processor.ModelDefinition.JoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import com.rey.modelquery.processor.ModelDefinition.WindowDefinition;
import com.rey.modelquery.processor.ModelDefinition.WindowOrder;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * Checks a {@link ModelDefinition} against its entity and against what {@link QModelWriter} can emit. Every check
 * runs whatever the others found, so one compilation reports every independent problem (R-DIAG-03).
 *
 * @implSpec R-GEN-03, R-PROC-05, R-PROC-07, R-PROC-08, R-PROC-10, R-PROC-11, R-PROC-12, R-PROC-16, R-DIAG-01,
 *     R-DIAG-02, R-DIAG-03
 */
final class ModelValidator {

    /** Constants every QModel may declare itself, which a field's constant must not take ({@code MQ3015}). */
    private static final Set<String> RESERVED = Set.of("ROOT", "ALL", "DEFAULT", "KEY", "MAPPER", "GROUP_KEYS");

    /** {@link #RESERVED} and the set a {@code @Selected} model's mapper reads, which only that model declares. */
    private static final Set<String> RESERVED_BY_SELECTED = Set.of(
            "ROOT", "ALL", "DEFAULT", "KEY", "MAPPER", "GROUP_KEYS", QModelWriter.SELECTED_FIELDS);

    /** The only constant an update model's generated class declares itself (R-GEN-19). */
    private static final Set<String> RESERVED_BY_UPDATE = Set.of("ROOT");

    /** The constant an insert model's, or a {@code generateInserts} query model's, class declares (R-GEN-28). */
    private static final String INSERT_COLUMNS = "INSERT_COLUMNS";

    /** The constants an insert model's generated class declares itself (R-GEN-28). */
    private static final Set<String> RESERVED_BY_INSERT = Set.of("ROOT", INSERT_COLUMNS);

    /** The type of a {@code @Selected} field, which the annotations module can't name (INV-7). */
    private static final String SELECT_SET = "com.rey.modelquery.core.SelectSet";

    /** What {@code @Selected} can't share a field with: every other annotation of the library that sits on one. */
    private static final List<Class<? extends Annotation>> NOT_WITH_SELECTED = List.of(
            Column.class, PrimaryKey.class, Transient.class, ExcludeFromDefaults.class, ExcludeFromInserts.class,
            Join.class, Child.class, Aggregate.class, GroupBy.class, Computed.class);

    private static final String LONG = "java.lang.Long";
    private static final String DOUBLE = "java.lang.Double";
    private static final String NUMBER = "java.lang.Number";
    private static final String COMPARABLE = "java.lang.Comparable";
    /** What {@code Agg.sum} sums to the column's own type (api/13 R-AGG-03). */
    private static final Set<String> SUMMABLE = Set.of("java.math.BigDecimal", DOUBLE, LONG);
    /** What {@code Agg.sumAsLong} sums; the database returns a {@code Long} (api/13 R-AGG-03). */
    private static final Set<String> INTEGRAL = Set.of("java.lang.Integer", "java.lang.Short", "java.lang.Byte");

    /** The window functions that take no argument, whose result is a {@code Long} (R-PROC-36). */
    private static final Set<String> WINDOW_RANKING = Set.of("ROW_NUMBER", "RANK", "DENSE_RANK");

    /** The window functions that aggregate a value; every other one orders the window and needs an {@code orderBy}. */
    private static final Set<String> WINDOW_AGGREGATES = Set.of("SUM", "AVG", "MIN", "MAX", "COUNT");

    private static final String LOMBOK_NO_ARGS = "lombok.NoArgsConstructor";

    private final Types types;
    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;
    private final BuiltInConverters builtIns;
    private final WriteChecks writeChecks;
    private final ChildChecks childChecks;

    ModelValidator(
            Types types, EntityMetamodel metamodel, NestedModels nestedModels, BuiltInConverters builtIns) {
        this.types = types;
        this.metamodel = metamodel;
        this.nestedModels = nestedModels;
        this.builtIns = builtIns;
        this.writeChecks = new WriteChecks(types, metamodel);
        this.childChecks = new ChildChecks(types, metamodel, nestedModels);
    }

    void validate(ModelDefinition model, Diagnostics diagnostics) {
        // A root that is neither an @Entity nor a @SourceModel, or both, decides no kind of read: every check below
        // would run against the wrong metamodel (MQ3038, R-PROC-39).
        if (model.readModel() && !rootKindOk(model, diagnostics)) {
            return;
        }
        // An update model is only read, and an insert model is instantiated by its caller, never by a mapper: neither
        // its shape nor its primitives matter.
        if (model.readModel()) {
            checkShape(model, diagnostics);
        }
        // A model with a @Selected field generates one more constant, which no field's may take (R-GEN-29).
        Set<String> reserved = switch (model.kind()) {
            case QUERY, SOURCE -> model.hasSelected() ? RESERVED_BY_SELECTED : RESERVED;
            case UPDATE -> RESERVED_BY_UPDATE;
            case INSERT -> RESERVED_BY_INSERT;
        };
        // A generateInserts query model declares INSERT_COLUMNS too (R-PROC-26).
        if (model.queryModel() && model.inserts()) {
            var withInserts = new HashSet<>(reserved);
            withInserts.add(INSERT_COLUMNS);
            reserved = Set.copyOf(withInserts);
            writeChecks.checkGeneratedInserts(model, diagnostics);
        }
        writeChecks.checkExcluded(model, diagnostics);
        if (model.readModel()) {
            checkSelected(model, diagnostics);
        }
        if (model.updateModel()) {
            writeChecks.checkUpdateOnly(model, diagnostics);
        }
        if (model.insertModel()) {
            writeChecks.checkInsertOnly(model, diagnostics);
            writeChecks.checkInsertKey(model, diagnostics);
        }
        if (model.changes()) {
            writeChecks.checkModel(model, diagnostics);
        }
        // A group has no row identity, so a summary model needs no key (R-AGG-09); an update model has no groups. An
        // insert model leaves a generated id out, which MQ3501 checks.
        if (model.keys().isEmpty() && (model.updateModel()
                || model.queryModel() && !model.readsSource() && model.aggregates().isEmpty())) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3004, model.name() + (model.updateModel()
                    ? ": no @PrimaryKey; update(...) and delete() choose rows by the entity id"
                    : ": no @PrimaryKey; paging, export and @Join presence need one"));
        }
        // A source's key is unique per row (R-SRC-02); an outer model's key is its source's own key (R-SRC-03).
        if (model.sourceModel()) {
            checkSourceKey(model, diagnostics);
            checkDiscriminatorKey(model, diagnostics);
            // A recursive source takes no window, aggregate or group by (R-PROC-37).
            if (model.recursive()) {
                checkRecursive(model, diagnostics);
            }
            // A branch maps its shape's fields one for one (R-PROC-38).
            if (model.branch()) {
                checkBranch(model, diagnostics);
            }
        } else if (model.readsSource()) {
            checkOuterKey(model, diagnostics);
            checkGenerateChanges(model, diagnostics);
        }
        // What each constant is generated for, as a clash names it: a joined column first, so that a field taking its
        // name is the one reported.
        var constants = new HashMap<String, String>();
        var joined = new ArrayList<JoinedTable>();
        // An update model's @Join is MQ3302, an insert model's MQ3502, and joins nothing.
        for (ModelField join : model.readModel() ? model.joins() : List.<ModelField>of()) {
            String where = model.name() + "." + join.name() + ": ";
            // A source root resolves no association, so an outer model reads its fields by name alone (R-SRC-10).
            if (model.readsSource()) {
                diagnostics.error(join.element(), DiagnosticCode.MQ3035, where + "@Join needs an entity root; "
                        + model.source().name() + " is a @SourceModel, whose columns an outer model reads by name");
                continue;
            }
            ModelDefinition nested = checkJoin(model, join, where, diagnostics);
            if (nested != null) {
                List<JoinedTable> tables = nestedModels.tables(model, join, nested);
                claimJoined(tables, constants, reserved, where, diagnostics);
                joined.addAll(tables);
            }
        }
        Set<String> ofJoins = Set.copyOf(constants.keySet());
        // A filter path is laid out over the joins that can be generated: one that failed is reported above. An outer
        // model over a source declares none: its fields are read by name, and no CTE column carries an alias of its
        // own (MQ3011, R-SRC-10).
        FilterLayout filters = model.readsSource()
                ? new FilterLayout(List.of(), List.of(), List.of())
                : FilterLayout.of(model, joined, metamodel);
        filters.problems().forEach(problem -> diagnostics.error(model.type(), problem.code(), problem.detail()));
        claimFilterTables(model, filters, constants, diagnostics);
        if (model.readModel()) {
            checkGrouping(model, diagnostics);
        }
        // Only an ungrouped query always selects the key: a grouped one selects it like any other column (D-49).
        boolean ungrouped = model.aggregates().isEmpty() && model.groupKeys().isEmpty();
        for (ModelField field : model.fields()) {
            if (!field.column() && field.aggregate() == null && !field.computed() && !field.windowed()) {
                continue;
            }
            String where = model.name() + "." + field.name() + ": ";
            // A field may carry @Computed beside @Aggregate, which MQ3019 reports: run both checks, not either.
            if (model.readModel() && field.aggregate() != null) {
                checkAggregate(model, field, where, diagnostics);
            }
            if (model.readModel() && field.computed()) {
                checkComputed(model, field, where, diagnostics);
            }
            // A window function is only valid over a source model: SQL forbids one in WHERE, and a keyset predicate
            // is a WHERE, so an outer model reads the value as a plain column (R-PROC-36).
            if (field.windowed()) {
                if (model.sourceModel()) {
                    checkWindow(model, field, where, diagnostics);
                } else {
                    diagnostics.error(field.element(), DiagnosticCode.MQ3033,
                            where + "@Window is only allowed on a @SourceModel");
                }
            }
            // A recursive source's @Depth is the level the CTE assigns the row, not a path into the root: only a
            // source model can be recursive, and its @Depth is one Integer (R-PROC-37).
            if (field.depth()) {
                if (model.sourceModel()) {
                    checkDepth(model, field, where, diagnostics);
                } else {
                    diagnostics.error(field.element(), DiagnosticCode.MQ3037, where
                            + "@Depth is only allowed on a @SourceModel; only a source recurses");
                }
            }
            // A branch's @Discriminator is a constant, never a path into the root: read the attribute check as
            // satisfied (R-PROC-38). A @Depth is the CTE's level column, read from no attribute either (R-PROC-37).
            if (field.column() && !field.discriminated() && !field.depth()) {
                checkAttribute(model, field, where, diagnostics);
            }
            if (field.column() && model.isRecord() && model.readModel() && field.type().getKind().isPrimitive()
                    && !(ungrouped && field.primaryKey())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3009,
                        where + "primitive components can't be null when not selected; use "
                                + display(types.boxedClass((PrimitiveType) field.type()).asType()));
            }
            if (field.join() != null) {
                // An @Aggregate on a @Join field is MQ3204; its constant is the join's own prefix, not a clash.
                continue;
            }
            claim(field, where, reserved, constants, ofJoins, diagnostics);
        }
        // An insert model's @Child is MQ3502.
        for (ModelField child : model.insertModel() ? List.<ModelField>of() : model.children()) {
            String childWhere = model.name() + "." + child.name() + ": ";
            // A source is one statement: it has no parent query to fill a @Child from (R-PROC-35).
            if (model.sourceModel()) {
                diagnostics.error(child.element(), DiagnosticCode.MQ3030,
                        childWhere + "@Child needs a parent query to fill it; a @SourceModel is one statement");
                continue;
            }
            childChecks.check(model, child, diagnostics);
            // Its ChildField constant is named as a column of the field would be (R-FCH-03).
            claim(child, model.name() + "." + child.name() + ": ", reserved, constants, ofJoins, diagnostics);
        }
        // An insert model's @FilterColumn is MQ3502, and declares no constant.
        for (FilterColumnDefinition column : model.insertModel() ? List.<FilterColumnDefinition>of()
                : model.filterColumns()) {
            String where = model.name() + " " + column.label() + ": ";
            // A filter column reads the CTE column its constant aliases; an outer model owns no CTE column of its
            // own, so it reads the source's fields by name alone (R-SRC-10).
            if (model.readsSource()) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3011, where + "an outer model reads a source's "
                        + "fields by field name; a @FilterColumn of its own has no CTE column to alias");
                continue;
            }
            if (!isSimpleName(column.name())) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3013, where + "name '" + column.name()
                        + "' can't be a constant's name; use a Java identifier");
            } else if (reserved.contains(column.name())) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3013,
                        where + "name is reserved by the generated class");
            } else {
                String earlier = constants.putIfAbsent(column.name(), "another @FilterColumn");
                if (earlier != null) {
                    diagnostics.error(model.type(), DiagnosticCode.MQ3013, where + "name already used by " + earlier);
                }
            }
        }
        for (FilterLayout.Column column : filters.columns()) {
            if (column.definition().converter() != null) {
                checkConverter(model, model.type(), column.definition().converter(), null, column.read(),
                        model.name() + " " + column.definition().label() + ": ", diagnostics);
            }
        }
        // An insert or update model generates no fields() (R-GEN-32).
        if (model.readModel()) {
            for (FilterIndex.Shadowed shadowed : FilterIndex.of(model, joined, filters).shadowed()) {
                diagnostics.warning(model.type(), DiagnosticCode.MQ3022, model.name() + " "
                        + shadowed.column().definition().label() + ": its key '" + shadowed.key() + "' is already "
                        + "held by " + shadowed.holder() + "; fields() leaves it out, and it stays a constant");
            }
        }
    }

    /**
     * Whether {@code model}'s {@code root} is the kind of read the model is: an {@code @Entity} for a source model,
     * and an {@code @Entity} or an {@code @SourceModel}, not both, for a query model. One that is neither, or both,
     * is {@code MQ3038} and decides no read (R-PROC-39).
     */
    private static boolean rootKindOk(ModelDefinition model, Diagnostics diagnostics) {
        boolean entity = QueryModelReader.isEntity(model.root());
        boolean source = QueryModelReader.isSourceModel(model.root());
        String name = model.name() + ": root " + model.root().getSimpleName();
        if (model.sourceModel() && source) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3038,
                    name + " is a @SourceModel; a source model reads an @Entity");
            return false;
        }
        if (entity == source) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3038, entity
                    ? name + " is both @Entity and @SourceModel; keep one"
                    : name + " is neither @Entity nor @SourceModel");
            return false;
        }
        return true;
    }

    /**
     * {@code MQ3031} for a source whose key is not unique per row: an ungrouped source's {@code @PrimaryKey} is the
     * root entity's id, a grouped one's is its full {@code @GroupBy} set with every group key non-nullable in its
     * mapping, and no field reads through a to-many association (R-SRC-02, R-SRC-03).
     */
    private void checkSourceKey(ModelDefinition model, Diagnostics diagnostics) {
        // A @Discriminator key column is a constant the branch fixes, not a path into the root: the key it takes
        // part in is the root's own key plus the discriminator (R-SRC-06).
        List<ModelField> keys = model.keys().stream().filter(key -> !key.discriminated()).toList();
        if (!model.grouped()) {
            Set<String> attributes = keys.stream().map(ModelField::attribute).collect(Collectors.toSet());
            if (keys.size() != 1 || !metamodel.id(model.root()).is(attributes)) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3031, model.name() + ": @PrimaryKey must be "
                        + model.root().getSimpleName() + "'s id " + metamodel.id(model.root()).label()
                        + ", one row's key; group the source to key it by its @GroupBy columns");
            }
            reportToManyReads(model, diagnostics);
            return;
        }
        List<ModelField> groupKeys = model.groupKeys();
        boolean keyIsGroup = keys.stream().map(ModelField::name).toList()
                .equals(groupKeys.stream().map(ModelField::name).toList());
        if (!keyIsGroup) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3031, model.name() + ": @PrimaryKey must be every "
                    + "@GroupBy column of the source, "
                    + groupKeys.stream().map(field -> "'" + field.name() + "'").collect(Collectors.joining(", "))
                    + ", since a group is one row");
        } else {
            for (ModelField key : keys) {
                EntityAttribute attribute = metamodel.resolve(model.root(), key.attribute()).attribute();
                if (attribute != null && attribute.nullable()) {
                    diagnostics.error(key.element(), DiagnosticCode.MQ3031, model.name() + "." + key.name()
                            + ": a group key must be non-nullable, and " + key.attribute() + " is nullable; add "
                            + "@Column(nullable = false) or an @Id");
                }
            }
        }
        reportToManyReads(model, diagnostics);
    }

    /**
     * {@code MQ3031} for a {@code @FilterColumn} of a source whose path crosses a to-many association: a source
     * reads one row per key, so the filter is an explicit {@code Filters.exists} instead (R-SRC-03).
     */
    private void reportToManyReads(ModelDefinition model, Diagnostics diagnostics) {
        for (FilterColumnDefinition column : model.filterColumns()) {
            EntityMetamodel.Walk walk = metamodel.walk(model.root(), column.path());
            String crossing = walk.steps().stream()
                    .filter(step -> step.attribute().kind() == EntityAttribute.Kind.COLLECTION)
                    .map(step -> step.attribute().name()).findFirst().orElse(null);
            if (crossing != null) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3031, model.name() + " " + column.label()
                        + ": a source reads one row per key, so its fields can't cross the to-many join '" + crossing
                        + "'; filter with Filters.exists");
            }
        }
    }

    /**
     * {@code MQ3037} for a {@code @Depth} that is not exactly one {@code Integer}: the level the CTE assigns a row is
     * an {@code Integer}, and a model declares one (R-PROC-37).
     */
    private static void checkDepth(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        if (!field.type().toString().equals("java.lang.Integer")) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3037,
                    where + "a recursive source takes one Integer @Depth");
            return;
        }
        List<ModelField> depths = model.depths();
        if (depths.indexOf(field) > 0) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3037, where + "a model declares one @Depth field, "
                    + "the level the CTE assigns its rows; the first is " + depths.get(0).name());
        }
    }

    /**
     * {@code MQ3037} for a recursive source that carries {@code @Window}, {@code @Aggregate} or {@code @GroupBy}:
     * every database forbids all three in the recursive term, so the source cannot recurse with one (R-PROC-37).
     */
    private static void checkRecursive(ModelDefinition model, Diagnostics diagnostics) {
        for (ModelField field : model.windows()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3037, model.name() + "." + field.name()
                    + ": a recursive source takes no @Window; a database forbids one in the recursive term");
        }
        for (ModelField field : model.aggregates()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3037, model.name() + "." + field.name()
                    + ": a recursive source takes no @Aggregate; a database forbids one in the recursive term");
        }
        for (ModelField field : model.groupKeys()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3037, model.name() + "." + field.name()
                    + ": a recursive source takes no @GroupBy; a database forbids one in the recursive term");
        }
    }

    /**
     * {@code MQ3034} for a {@code @Discriminator} that is not part of its model's key: the merged rows are keyed by
     * the shape's own key, which carries the discriminator (R-SRC-06).
     */
    private static void checkDiscriminatorKey(ModelDefinition model, Diagnostics diagnostics) {
        List<ModelField> discriminators = model.discriminators();
        for (int i = 0; i < discriminators.size(); i++) {
            ModelField field = discriminators.get(i);
            if (!field.primaryKey()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3034, model.name() + "." + field.name()
                        + ": @Discriminator must be part of the @PrimaryKey; a branch is keyed by the constant it "
                        + "fixes (R-SRC-06)");
            }
            if (!field.type().toString().equals("java.lang.String")) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3034, model.name() + "." + field.name()
                        + ": @Discriminator fixes a String constant, so the field is a String (R-SRC-06)");
            }
            // DECIDE: R-SRC-06 makes one discriminator column disjoint the branches of a union; the builder fixes
            // one, and a second field could only be fixed by ignoring this one.
            if (i == 1) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3034, model.name() + "." + field.name()
                        + ": a model declares one @Discriminator field, the constant that tells its branches apart; "
                        + "the first is " + discriminators.get(0).name());
            }
        }
    }

    /**
     * {@code MQ3034} for a branch model whose fields do not map its shape's (R-PROC-38): a branch maps the shape's
     * fields one for one, by name, in declaration order, by Java type, by what the database stores (the converter,
     * the {@code EnumType} and the temporal type) and by key. A branch declares no {@code @Window}, and neither does a
     * shape that has branches.
     */
    private void checkBranch(ModelDefinition model, Diagnostics diagnostics) {
        ModelDefinition shape = model.shape();
        String byName = "a branch maps the shape's fields one for one, by name, in declaration order";
        for (ModelField field : model.windows()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3034, model.name() + "." + field.name()
                    + ": a branch declares no @Window field; a window over the combined rows is not supported "
                    + "(R-PROC-38)");
        }
        for (ModelField field : shape.windows()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3034, model.name() + ": the shape " + shape.name()
                    + " declares the @Window field " + field.name() + ", which no branch can combine with; a shape "
                    + "with branches declares no @Window (R-PROC-38)");
        }
        // A recursive model is neither a branch nor a shape: a shape's branches are combined, and the recursion has
        // one seed and one step (R-PROC-37).
        for (ModelField field : model.depths()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3034, model.name() + "." + field.name()
                    + ": a branch declares no @Depth; a recursive model is neither a branch nor a shape");
        }
        for (ModelField field : shape.depths()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3034, model.name() + ": the shape " + shape.name()
                    + " declares the @Depth field " + field.name() + ", which no branch can recurse from; a recursive "
                    + "model is neither a branch nor a shape");
        }
        List<ModelField> fields = model.fields();
        List<ModelField> theirs = shape.fields();
        for (int i = 0; i < fields.size(); i++) {
            ModelField field = fields.get(i);
            String where = model.name() + "." + field.name() + ": ";
            if (i >= theirs.size()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3034, where + "the shape " + shape.name()
                        + " declares no field at this position; " + byName);
                continue;
            }
            ModelField other = theirs.get(i);
            if (!field.name().equals(other.name())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3034, where + "the shape " + shape.name()
                        + " declares '" + other.name() + "' here; " + byName);
                continue;
            }
            if (!types.isSameType(boxed(field.type()), boxed(other.type()))) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3034, where + "branch " + model.name()
                        + " maps it as " + display(field.type()) + ", the shape " + shape.name() + " as "
                        + display(other.type()) + "; a set operation compares the stored values");
                continue;
            }
            checkBranchStorage(model, shape, field, other, where, diagnostics);
        }
        for (int i = fields.size(); i < theirs.size(); i++) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3034, model.name() + ": the shape " + shape.name()
                    + " declares the field '" + theirs.get(i).name() + "' after every field of the branch; " + byName);
        }
        List<String> keys = model.keys().stream().map(ModelField::name).toList();
        List<String> shapeKeys = shape.keys().stream().map(ModelField::name).toList();
        if (!keys.equals(shapeKeys)) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3034, model.name() + ": @PrimaryKey must be the shape "
                    + shape.name() + "'s key " + quoted(shapeKeys) + ", by name and in order; the branches of a set "
                    + "operation are matched by the shape's key");
        }
        checkBranchDiscriminator(model, shape, diagnostics);
    }

    /**
     * {@code MQ3034} for a branch column that stores other values than its shape's (R-PROC-38): the converter class,
     * the {@code EnumType} and the temporal type. An {@code ORDINAL} enum and a {@code STRING} one, or a converted
     * column and a raw one, would otherwise compile and produce rows that compare unequal.
     */
    private void checkBranchStorage(ModelDefinition model, ModelDefinition shape, ModelField field, ModelField other,
            String where, Diagnostics diagnostics) {
        if (field.converter() == null ? other.converter() != null
                : other.converter() == null || !types.isSameType(field.converter(), other.converter())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3034, where + "branch " + model.name()
                    + " converts it with " + converterName(field.converter()) + ", the shape " + shape.name()
                    + " with " + converterName(other.converter()) + "; a set operation compares the stored values");
        }
        EntityAttribute mine = metamodel.resolve(model.root(), field.attribute()).attribute();
        EntityAttribute theirs = metamodel.resolve(shape.root(), other.attribute()).attribute();
        if (mine == null || theirs == null) {
            // The attribute check reports the path itself, on the model whose field names it.
            return;
        }
        // @Enumerated defaults to ORDINAL, so writing it out is not a difference.
        String mineEnum = mine.enumType() == null ? "ORDINAL" : mine.enumType();
        String theirsEnum = theirs.enumType() == null ? "ORDINAL" : theirs.enumType();
        if (!mineEnum.equals(theirsEnum)) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3034, where + model.root().getSimpleName() + "."
                    + field.attribute() + " stores it as @Enumerated(" + mineEnum + "), and "
                    + shape.root().getSimpleName() + "." + other.attribute() + " as @Enumerated(" + theirsEnum
                    + "); a set operation compares the stored values");
        }
        if (!Objects.equals(mine.temporal(), theirs.temporal())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3034, where + model.root().getSimpleName() + "."
                    + field.attribute() + " stores it as @Temporal("
                    + (mine.temporal() == null ? "the default" : mine.temporal()) + "), and "
                    + shape.root().getSimpleName() + "." + other.attribute() + " as @Temporal("
                    + (theirs.temporal() == null ? "the default" : theirs.temporal())
                    + "); a set operation compares the stored values");
        }
    }

    /**
     * {@code MQ3034} for a branch of a discriminated shape that does not declare the shape's {@code @Discriminator}
     * field: the constant it fixes is what keeps the branches' keys disjoint (R-SRC-06).
     */
    private static void checkBranchDiscriminator(ModelDefinition model, ModelDefinition shape,
            Diagnostics diagnostics) {
        List<String> shapeFields = shape.discriminators().stream().map(ModelField::name).toList();
        if (shapeFields.isEmpty()) {
            return;
        }
        List<String> fields = model.discriminators().stream().map(ModelField::name).toList();
        if (!fields.equals(shapeFields)) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3034, model.name() + ": the shape " + shape.name()
                    + " carries @Discriminator on " + quoted(shapeFields) + ", and every branch declares the same; "
                    + model.name() + " declares " + (fields.isEmpty() ? "none" : "it on " + quoted(fields))
                    + " (R-SRC-06)");
        }
    }

    /** {@code value} as a diagnostic names a converter: its simple name, or {@code null} for no converter. */
    private static String converterName(TypeMirror converter) {
        return converter == null ? "no converter" : display(converter);
    }

    /** {@code names} as a diagnostic lists a key: {@code 'a', 'b'}. */
    private static String quoted(List<String> names) {
        return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
    }

    /**
     * {@code MQ3036} for an outer model whose {@code @PrimaryKey} is not exactly its source's key, in the same order:
     * the engine's tie-breakers and a keyset cursor read the source's own key (R-SRC-03).
     */
    private static void checkOuterKey(ModelDefinition model, Diagnostics diagnostics) {
        List<String> sourceKey = model.source().keys().stream().map(ModelField::name).toList();
        List<String> written = model.keys().stream().map(ModelField::attribute).toList();
        if (!written.equals(sourceKey)) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3036, model.name() + ": @PrimaryKey must be "
                    + model.source().name() + "'s key "
                    + sourceKey.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "))
                    + ", the source's own key; a keyset cursor and the engine's tie-breakers read it");
        }
    }

    /**
     * {@code MQ3040} for a model rooted at a source that sets {@code generateChanges}: a write needs an entity root,
     * and neither a source nor an outer model over one generates a change set, so the flag is reported rather than
     * ignored (R-GEN-37, D-145). {@code @SourceModel} declares no {@code generateChanges} member, so an outer model
     * is the only kind that reaches this.
     */
    private static void checkGenerateChanges(ModelDefinition model, Diagnostics diagnostics) {
        if (model.generateChanges()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3040, model.name()
                    + ": generateChanges needs an entity root; it is rooted at source " + model.source().name()
                    + ", a @SourceModel no change set can write");
        }
    }

    /**
     * {@code MQ3001} for an outer column naming no field of its source, {@code MQ3002} for one of another type, and
     * {@code MQ3014} for a converter of its own: an outer model reads the CTE's columns by field name and takes the
     * source column's type, alias and converter (R-SRC-10, INV-3).
     */
    private void checkSourceAttribute(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        if (field.converter() != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3014, where + "an outer column reads its source's "
                    + "converter; drop @Column(converter = ...) or declare it on the source's own field");
            return;
        }
        ModelField source = model.sourceField(field.attribute());
        if (source == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + "'" + field.attribute()
                    + "' is no field of " + model.source().name() + ", the source this model reads; an outer model "
                    + "reads a source's fields by name");
        } else if (!types.isSameType(boxed(field.type()), boxed(source.type()))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "model type " + display(field.type())
                    + ", the source's " + source.name() + " is " + display(source.type()));
        }
    }

    /**
     * {@code MQ3032} for a {@code @Window} that is not well written (R-PROC-36): a term that names no attribute of
     * the root (an {@code @Aggregate} field or a group key in a grouped source), an argument where the function takes
     * none or none where it needs one, a field type that is not the function's result type (INV-3), an
     * order-sensitive function without {@code orderBy}, a missing frame, a {@code RANGE} frame on
     * {@code FIRST_VALUE} or {@code LAST_VALUE}, or a frame on a function that takes none.
     */
    private void checkWindow(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        WindowDefinition window = field.window();
        String fn = window.function();
        boolean aggregate = WINDOW_AGGREGATES.contains(fn);
        boolean ranking = WINDOW_RANKING.contains(fn);
        boolean hasFrame = !window.frame().equals("NONE");

        // Condition 2: an argument exactly where one is needed.
        if (ranking && !window.argument().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032,
                    where + fn + " takes no argument; drop argument");
        } else if (!ranking && window.argument().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032,
                    where + fn + " needs an argument; write @Window(argument = \"...\")");
        }

        // Conditions 1 and 8: every term names an attribute, or an aggregate field or group key of a grouped source.
        boolean grouped = !model.groupKeys().isEmpty();
        TypeMirror argumentType = null;
        if (!window.argument().isEmpty()) {
            argumentType = windowTerm(model, grouped, window.argument(), "argument", field, where, diagnostics);
        }
        for (String path : window.partitionBy()) {
            windowTerm(model, grouped, path, "partitionBy", field, where, diagnostics);
        }
        for (WindowOrder order : window.orderBy()) {
            windowTerm(model, grouped, order.path(), "orderBy", field, where, diagnostics);
        }

        // Condition 4: a function that orders the window, or an aggregate over a ROWS frame, must say how.
        if ((!aggregate || window.frame().startsWith("ROWS_")) && window.orderBy().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032,
                    where + fn + " is order-sensitive; add orderBy");
        }

        // Condition 5: FIRST_VALUE, LAST_VALUE and an ordered aggregate need an explicit frame.
        if (!hasFrame && (fn.equals("FIRST_VALUE") || fn.equals("LAST_VALUE")
                || aggregate && !window.orderBy().isEmpty())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032, where + fn
                    + " needs an explicit frame; with the database's default frame "
                    + (fn.equals("LAST_VALUE") ? "LAST_VALUE returns the current row" : "the window is unspecified"));
        }

        // Condition 6: a RANGE frame would make every row its own peer group for a value function.
        if (window.frame().startsWith("RANGE_") && (fn.equals("FIRST_VALUE") || fn.equals("LAST_VALUE"))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032,
                    where + fn + " takes only a ROWS frame; a RANGE frame would make every row its own peer group");
        }

        // Condition 7: ranking functions and LAG/LEAD take no frame, which the database ignores or rejects.
        if (hasFrame && (ranking || fn.equals("LAG") || fn.equals("LEAD"))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032,
                    where + fn + " takes no frame; drop frame");
        }

        // Condition 3: the field's type is the function's result type, which a ranking function's does not depend on
        // its argument; an argument that names nothing, or is missing, is reported above.
        if (argumentType != null || ranking) {
            checkWindowType(fn, argumentType, field, where, diagnostics);
        }
    }

    /**
     * The type of a window term: its attribute's type, or an {@code @Aggregate} field's type in a grouped source.
     * {@code MQ3032} when it names no attribute of the root, is an association, or in a grouped source is neither a
     * group key nor an aggregate (R-PROC-36).
     */
    private TypeMirror windowTerm(ModelDefinition model, boolean grouped, String term, String kind, ModelField field,
            String where, Diagnostics diagnostics) {
        if (grouped) {
            ModelField aggregate = model.aggregates().stream()
                    .filter(each -> each.name().equals(term)).findFirst().orElse(null);
            if (aggregate != null) {
                return boxed(aggregate.type());
            }
            ModelField groupKey = model.groupKeys().stream()
                    .filter(each -> each.attribute().equals(term)).findFirst().orElse(null);
            if (groupKey != null) {
                return boxed(groupKey.type());
            }
            diagnostics.error(field.element(), DiagnosticCode.MQ3032, where + kind + " '" + term
                    + "' is neither a group key nor an @Aggregate of this grouped source; name one of GROUP_KEYS or "
                    + "an aggregate field");
            return null;
        }
        Resolution resolution = metamodel.resolve(model.root(), term);
        if (resolution.attribute() == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032,
                    where + kind + " '" + term + "': " + resolution.problem());
            return null;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute.kind() == EntityAttribute.Kind.TO_ONE || attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032, where + kind + " '" + term
                    + "' is an association on " + model.root().getSimpleName()
                    + "; a window term is a basic attribute");
            return null;
        }
        return boxed(attribute.type());
    }

    /**
     * {@code MQ3032} when {@code field}'s type is not {@code fn}'s result type over {@code argumentType} (INV-3):
     * {@code Long} for a ranking function or {@code COUNT}, the argument's type for {@code LAG}, {@code LEAD},
     * {@code FIRST_VALUE}, {@code LAST_VALUE}, {@code MIN} and {@code MAX}, and the rule {@code @Aggregate}'s
     * {@code SUM} and {@code AVG} use for those two (R-PROC-36).
     */
    private void checkWindowType(String fn, TypeMirror argumentType, ModelField field, String where,
            Diagnostics diagnostics) {
        // Only a ranking function reaches this without an argument: it returns Long whatever it reads.
        String expected;
        switch (fn) {
            case "ROW_NUMBER", "RANK", "DENSE_RANK", "COUNT" -> expected = LONG;
            case "SUM" -> {
                if (isOneOf(argumentType, SUMMABLE)) {
                    expected = qualified(argumentType);
                } else if (isOneOf(argumentType, INTEGRAL)) {
                    expected = LONG;
                } else {
                    diagnostics.error(field.element(), DiagnosticCode.MQ3032, where + "SUM over "
                            + display(argumentType) + " is not supported; it sums BigDecimal, Double, Long, Integer, "
                            + "Short and Byte");
                    return;
                }
            }
            case "AVG" -> {
                if (isSubtypeOf(argumentType, NUMBER)) {
                    expected = DOUBLE;
                } else {
                    diagnostics.error(field.element(), DiagnosticCode.MQ3032, where + "AVG over "
                            + display(argumentType) + " is not supported; it averages a numeric attribute");
                    return;
                }
            }
            default -> expected = qualified(argumentType);
        }
        if (!qualified(boxed(field.type())).equals(expected)) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3032,
                    where + fn + " returns " + simpleName(expected) + ", field is " + display(boxed(field.type())));
        }
    }

    /**
     * {@code MQ3020} for a {@code @Selected} field whose type is not exactly {@code SelectSet<Model>}, and for a
     * second {@code @Selected} field, and {@code MQ3021} for each annotation of the library it is combined with
     * (R-PROC-25). The type is checked by name, since the annotations module doesn't depend on {@code core}.
     */
    private static void checkSelected(ModelDefinition model, Diagnostics diagnostics) {
        boolean first = true;
        for (ModelField field : model.selected()) {
            String where = model.name() + "." + field.name() + ": ";
            if (!first) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3020, where
                        + "a model has one @Selected field; remove this one");
            }
            first = false;
            if (!isSelectSetOf(model, field.type())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3020, where + "@Selected field is "
                        + display(field.type()) + ", not SelectSet<" + model.name() + ">");
            }
            for (Class<? extends Annotation> other : NOT_WITH_SELECTED) {
                if (field.element().getAnnotation(other) != null) {
                    diagnostics.error(field.element(), DiagnosticCode.MQ3021,
                            where + "@Selected can't be combined with @" + other.getSimpleName());
                }
            }
        }
    }

    /** Whether {@code type} is {@code SelectSet<M>} for the model {@code M} itself, and nothing wider or raw. */
    private static boolean isSelectSetOf(ModelDefinition model, TypeMirror type) {
        return type instanceof DeclaredType declared
                && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(SELECT_SET)
                && declared.getTypeArguments().size() == 1
                && declared.getTypeArguments().get(0) instanceof DeclaredType argument
                && argument.asElement().equals(model.type());
    }

    /** {@code MQ3015} for a field whose constant is reserved, or already generated for something else. */
    private static void claim(ModelField field, String where, Set<String> reserved, Map<String, String> constants,
            Set<String> ofJoins, Diagnostics diagnostics) {
        String earlier = constants.putIfAbsent(field.constant(), "field '" + field.name() + "'");
        if (reserved.contains(field.constant())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "constant " + field.constant()
                    + " is reserved by the generated class; rename the field");
        } else if (earlier != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "constant " + field.constant()
                    + " is also generated for " + earlier + "; rename the field"
                    + (ofJoins.contains(field.constant()) ? " or set @Join(prefix)" : ""));
        }
    }

    /**
     * {@code MQ3204} for a {@code @GroupBy} on an {@code @Aggregate} or {@code @Join} field or an {@code @Aggregate}
     * beside {@code @PrimaryKey}, {@code @Column}, {@code @Join} or {@code @Transient}, {@code MQ3207} for
     * {@code singleGroup} with a {@code @GroupBy}, {@code MQ3203} for aggregates that no {@code @GroupBy} groups,
     * unless the model says it is a whole-table total (R-PROC-05).
     */
    private static void checkGrouping(ModelDefinition model, Diagnostics diagnostics) {
        for (ModelField field : model.fields()) {
            if (field.groupBy() && (field.aggregate() != null || field.join() != null)) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3204, model.name() + "." + field.name()
                        + ": @GroupBy can't be combined with " + (field.aggregate() != null ? "@Aggregate" : "@Join"));
            }
        }
        for (ModelField field : model.fields()) {
            for (String annotation : combinedWith(field)) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3204, model.name() + "." + field.name()
                        + ": @Aggregate can't be combined with " + annotation);
            }
        }
        if (model.singleGroup() && model.fields().stream().anyMatch(ModelField::groupBy)) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3207, model.name()
                    + ": singleGroup = true can't be combined with @GroupBy fields; remove one");
        }
        if (!model.aggregates().isEmpty() && model.groupKeys().isEmpty() && !model.singleGroup()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3203, model.name()
                    + ": has @Aggregate fields but no @GroupBy; add one or set @QueryModel(singleGroup = true)");
        }
    }

    /**
     * {@code MQ3201} for a primitive field, {@code MQ3202} for a type that is not what the function returns,
     * {@code MQ3205} for a {@code SUM} over a 32-bit attribute, {@code MQ3206} for {@code distinct} on any function
     * but {@code COUNT}, {@code MQ3001} or {@code MQ3002} for an attribute the function cannot read (R-AGG-03,
     * R-AGG-04). A primitive field is reported once, with the type the function returns.
     */
    private void checkAggregate(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        AggregateDefinition aggregate = field.aggregate();
        String fn = aggregate.fn();
        if (!combinedWith(field).isEmpty()) {
            return;
        }
        if (aggregate.distinct() && !fn.equals("COUNT")) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3206,
                    where + "distinct only applies to COUNT, found " + fn);
        }
        if (aggregate.expression() != null) {
            if (!aggregate.attribute().isEmpty()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3208,
                        where + "@Aggregate takes attribute or expression, not both");
                return;
            }
            checkExpressionAggregate(model, field, where, diagnostics);
            return;
        }
        reportAggregate(field, fn, expected(model, field, where, diagnostics), where, diagnostics);
    }

    /**
     * {@code MQ3018} for an {@code @Aggregate(expression)} class that is no usable
     * {@code ExpressionDefinition<Model, C>}, then the field's type against the function's result over the
     * expression's type {@code C} (R-PROC-22).
     */
    private void checkExpressionAggregate(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        AggregateDefinition aggregate = field.aggregate();
        ExpressionDefinitionType definition = ExpressionDefinitionType.of(types, aggregate.expression());
        if (!usableDefinition(model, field, where, definition, diagnostics)) {
            return;
        }
        reportAggregate(field, aggregate.fn(), expectedResult(definition.value(), false, field, where, diagnostics),
                where, diagnostics);
    }

    /**
     * Whether {@code definition} is a usable {@code ExpressionDefinition<Model, ?>}: of this model, and with an
     * instance or a no-arg constructor. Reports {@code MQ3018} on the field otherwise.
     */
    private boolean usableDefinition(ModelDefinition model, ModelField field, String where,
            ExpressionDefinitionType definition, Diagnostics diagnostics) {
        if (definition != null && !definition.isVisibleTo(model.packageName())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3018, where + display(field.computed()
                    ? field.definition() : field.aggregate().expression()) + " is not visible to "
                    + model.generatedName() + ", which is generated in " + model.packageName() + "; make it public");
            return false;
        }
        if (definition == null || !types.isSameType(definition.model(), model.type().asType())
                || (!definition.hasInstance() && !definition.hasVisibleConstructor(model.packageName()))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3018, definitionProblem(model, field, where));
            return false;
        }
        return true;
    }

    /**
     * {@code MQ3018} for a {@code @Computed} class that is no usable {@code ExpressionDefinition<Model, FieldType>},
     * and {@code MQ3019} for each annotation it can't share the field with and for a primitive field (R-PROC-21).
     */
    private void checkComputed(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        if (field.definition().getKind() == TypeKind.VOID) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3025, where + (model.origin() == model.type()
                    ? "@Computed names no ExpressionDefinition; name one, as @Computed(Def.class)"
                    : "@Computed names no ExpressionDefinition, and no @BindExpression on "
                            + ModelBindings.name(model.origin()) + " names one; add @BindExpression(field = \""
                            + field.name() + "\", value = Def.class)"));
            return;
        }
        if (field.type().getKind().isPrimitive()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3019,
                    where + "@Computed field is primitive; an expression may be NULL");
        }
        for (String annotation : computedCombinedWith(field)) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3019,
                    where + "@Computed can't be combined with " + annotation);
        }
        ExpressionDefinitionType definition = ExpressionDefinitionType.of(types, field.definition());
        if (!usableDefinition(model, field, where, definition, diagnostics)) {
            return;
        }
        if (!types.isSameType(definition.value(), boxed(field.type()))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3018, definitionProblem(model, field, where));
        }
    }

    /**
     * The {@code MQ3018} detail: the class the field names, and the {@code ExpressionDefinition} the field needs. For
     * {@code @Aggregate(expression)} the message names the field's type, since a class that implements no
     * {@code ExpressionDefinition} has no expression type to name.
     */
    private String definitionProblem(ModelDefinition model, ModelField field, String where) {
        TypeMirror named = field.computed() ? field.definition() : field.aggregate().expression();
        return where + display(named) + " is not an ExpressionDefinition<" + model.name() + ", "
                + display(boxed(field.type())) + ">, or has neither INSTANCE nor a no-arg constructor";
    }

    /** {@code MQ3201}, {@code MQ3202} or {@code MQ3205} for a field that is not what the aggregate returns. */
    private void reportAggregate(
            ModelField field, String fn, Expected expected, String where, Diagnostics diagnostics) {
        TypeMirror fieldType = boxed(field.type());
        if (field.type().getKind().isPrimitive()) {
            String use = expected == null ? display(fieldType) : simpleName(expected.type());
            diagnostics.error(field.element(), DiagnosticCode.MQ3201, where + (fn.equals("COUNT")
                    ? "COUNT is NULL-safe only as an object; use " : fn + " is NULL over zero rows; use ")
                    + use + ", not a primitive");
        } else if (expected != null && !qualified(fieldType).equals(expected.type())) {
            if (expected.thirtyTwoBit()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3205, where + expected.subject()
                        + " returns Long; declare the field as Long");
            } else {
                diagnostics.error(field.element(), DiagnosticCode.MQ3202, where + expected.subject() + " returns "
                        + simpleName(expected.type()) + ", field is " + display(fieldType));
            }
        }
    }

    /** What an aggregate returns: the qualified class, how a message names the call, and whether it sums 32 bits. */
    private record Expected(String type, String subject, boolean thirtyTwoBit) {}

    /**
     * The result of {@code field}'s aggregate, or {@code null} with {@code MQ3001}, {@code MQ3002} or {@code MQ3202}
     * when its attribute cannot be read by the function.
     */
    private Expected expected(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        AggregateDefinition aggregate = field.aggregate();
        String fn = aggregate.fn();
        boolean count = fn.equals("COUNT");
        if (count && aggregate.attribute().isEmpty() && !aggregate.distinct()) {
            return new Expected(LONG, "COUNT", false);
        }
        if (aggregate.attribute().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + fn
                    + (count ? " distinct" : "") + " needs an attribute to read; write @Aggregate(attribute)");
            return null;
        }
        // An aggregate over a model rooted at a source reads a source field, and takes that field's type: the CTE
        // column carries the conversion the source's own constant declares (R-SRC-10).
        if (model.readsSource()) {
            ModelField source = model.sourceField(aggregate.attribute());
            if (source == null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + "no field '"
                        + aggregate.attribute() + "' in " + model.source().name() + ", the source this model reads");
                return null;
            }
            return expectedResult(boxed(source.type()), true, field, where, diagnostics);
        }
        Resolution resolution = metamodel.resolve(model.root(), aggregate.attribute());
        if (resolution.attribute() == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + resolution.problem());
            return null;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute.kind() == EntityAttribute.Kind.TO_ONE || attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "@Aggregate attribute '"
                    + attribute.name() + "' is an association on " + model.root().getSimpleName()
                    + "; aggregate a basic attribute");
            return null;
        }
        return expectedResult(boxed(attribute.type()), true, field, where, diagnostics);
    }

    /**
     * The result of {@code field}'s aggregate over {@code source}, or {@code null} with {@code MQ3202} when the
     * function cannot read it. {@code converted} allows the built-in ordered converter a {@code MIN} or {@code MAX}
     * over a {@code Timestamp} attribute reads through (D-84), which an already-typed expression does not need.
     */
    private Expected expectedResult(
            TypeMirror source, boolean converted, ModelField field, String where, Diagnostics diagnostics) {
        String fn = field.aggregate().fn();
        String sourceName = display(source);
        String over = fn + " over " + sourceName;
        switch (fn) {
            case "COUNT" -> {
                return new Expected(LONG, "COUNT", false);
            }
            case "SUM" -> {
                if (isOneOf(source, SUMMABLE)) {
                    return new Expected(qualified(source), over, false);
                }
                if (isOneOf(source, INTEGRAL)) {
                    // Agg.sum refuses a 32-bit column; the field is a Long and the QModel sums it as one.
                    return new Expected(LONG, over, true);
                }
                diagnostics.error(field.element(), DiagnosticCode.MQ3202, where + "SUM over " + sourceName
                        + " is not supported; it sums BigDecimal, Double, Long, Integer, Short and Byte");
            }
            case "AVG" -> {
                if (isSubtypeOf(source, NUMBER)) {
                    return new Expected(DOUBLE, over, false);
                }
                diagnostics.error(field.element(), DiagnosticCode.MQ3202,
                        where + "AVG over " + sourceName + " is not supported; it averages a numeric attribute");
            }
            default -> {
                // MIN and MAX over a Timestamp read through a built-in ordered converter as the field's type (D-84).
                if (converted && builtIns.between(field.type(), source) != null) {
                    return new Expected(qualified(field.type()), over, false);
                }
                if (isSubtypeOf(source, COMPARABLE)) {
                    return new Expected(qualified(source), over, false);
                }
                diagnostics.error(field.element(), DiagnosticCode.MQ3202, where + over
                        + " is not supported; " + fn + " needs a Comparable attribute");
            }
        }
        return null;
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** The annotations {@code @Aggregate} can't share a field with, as written on {@code field}. */
    private static List<String> combinedWith(ModelField field) {
        var found = new ArrayList<String>();
        if (field.aggregate() == null) {
            return found;
        }
        if (field.primaryKey()) {
            found.add("@PrimaryKey");
        }
        if (field.element().getAnnotation(Column.class) != null) {
            found.add("@Column");
        }
        if (field.join() != null) {
            found.add("@Join");
        }
        if (field.element().getAnnotation(Transient.class) != null) {
            found.add("@Transient");
        }
        return found;
    }

    /** The annotations {@code @Computed} can't share a field with, as written on {@code field} (R-PROC-21). */
    private static List<String> computedCombinedWith(ModelField field) {
        var found = new ArrayList<String>();
        if (field.primaryKey()) {
            found.add("@PrimaryKey");
        }
        if (field.element().getAnnotation(Column.class) != null) {
            found.add("@Column");
        }
        if (field.join() != null) {
            found.add("@Join");
        }
        if (field.child() != null) {
            found.add("@Child");
        }
        if (field.aggregate() != null) {
            found.add("@Aggregate");
        }
        if (field.element().getAnnotation(Transient.class) != null) {
            found.add("@Transient");
        }
        return found;
    }

    /** The qualified name of {@code type}'s class, or {@code ""} when it is no class. */
    static String qualified(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED
                ? ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString() : "";
    }

    private static boolean isOneOf(TypeMirror type, Set<String> classes) {
        return classes.contains(qualified(type));
    }

    /** Whether {@code type} is, or extends or implements, the class named {@code name}, through its supertypes. */
    private boolean isSubtypeOf(TypeMirror type, String name) {
        if (qualified(type).equals(name)) {
            return true;
        }
        return types.directSupertypes(type).stream().anyMatch(supertype -> isSubtypeOf(supertype, name));
    }

    /** Whether {@code name} may be written where Java takes a simple name: an identifier that is no keyword. */
    private static boolean isSimpleName(String name) {
        return SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
    }

    /**
     * {@code MQ3015} for a join of a filter column, or of a collection of the root, whose constant is already
     * generated for something else, or whose alias can't start that constant's name.
     */
    private static void claimFilterTables(
            ModelDefinition model, FilterLayout filters, Map<String, String> constants, Diagnostics diagnostics) {
        for (FilterLayout.Table table : filters.tables()) {
            String where = model.name() + (table.typedBy() == null ? "" : " @FilterColumn(" + table.typedBy() + ")")
                    + ": ";
            if (!table.alias().isEmpty() && !isSimpleName(table.alias())) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3015, where + "alias '" + table.alias()
                        + "' can't start the name of its join's constant; use a Java identifier");
                continue;
            }
            String earlier = constants.putIfAbsent(table.constant(), table.description());
            if (earlier != null) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3015, where + "constant " + table.constant() + " of "
                        + table.description() + " is also generated for " + earlier
                        + "; set another @Join(prefix) or @FilterColumn(alias)");
            }
        }
    }

    /**
     * Why the model a bound model nests or holds is none: it is neither annotated nor bound in this compilation
     * ({@code MQ3026}, R-PROC-31).
     */
    static String unbound(ModelDefinition model, TypeMirror nested) {
        return display(nested) + " is neither annotated @QueryModel nor bound by @BindQueryModel in this compilation; "
                + "bind it with @BindQueryModel, as " + ModelBindings.name(model.origin()) + " binds " + model.name();
    }

    /**
     * Checks one {@code @Join} and returns the model it nests, or {@code null} when the join cannot be generated:
     * {@code MQ3005} for the field's type, {@code MQ3003} for the association, {@code MQ3006} for a nested model
     * without a key, {@code MQ3007} for a cycle.
     */
    private ModelDefinition checkJoin(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        JoinDefinition join = field.join();
        ModelDefinition nested = nestedModels.of(field);
        if (join.nested() == null) {
            boolean isModel = field.type().getKind() == TypeKind.DECLARED
                    && nestedModels.isModel((TypeElement) ((DeclaredType) field.type()).asElement());
            String expected = isModel ? "Optional<" + display(field.type()) + ">" : "Optional<X> of a @QueryModel X";
            diagnostics.error(field.element(), DiagnosticCode.MQ3005,
                    where + "@Join field must be " + expected + ", found " + display(field.type()));
        } else if (nested == null && model.origin() != model.type()
                && !nestedModels.isModel((TypeElement) ((DeclaredType) join.nested()).asElement())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3026, where + unbound(model, join.nested()));
        } else if (nested == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3005,
                    where + display(join.nested()) + " is neither annotated @QueryModel"
                            + " nor bound by @BindQueryModel in this compilation");
        }
        boolean joinable = nested != null;
        if (!isSimpleName(join.prefix())) {
            // The prefix starts every constant of the join, so it must be one itself.
            diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "@Join(prefix = \"" + join.prefix()
                    + "\") can't start a constant's name; use a Java identifier such as "
                    + QueryModelReader.constantName(field.name()));
            joinable = false;
        }
        TypeElement target = checkAssociation(model, field, where, diagnostics);
        if (target == null) {
            joinable = false;
        } else if (nested != null && !target.equals(nested.root())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3003, where + nested.name() + ".root is "
                    + nested.root().getSimpleName() + ", association targets " + target.getSimpleName());
            joinable = false;
        }
        if (nested == null) {
            return null;
        }
        if (!nested.aggregates().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3005, where + "@Join model " + nested.name()
                    + " has @Aggregate fields; a summary model can't be joined");
            joinable = false;
        } else if (!nested.computed().isEmpty()) {
            // A computed expression is built against its own model's columns, which a joined row cannot provide.
            diagnostics.error(field.element(), DiagnosticCode.MQ3005, where + "@Join model " + nested.name()
                    + " has @Computed fields; a model with a computed field can't be joined");
            joinable = false;
        } else if (nested.keys().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3006,
                    where + nested.name() + " needs a @PrimaryKey to be used in @Join");
            joinable = false;
        }
        String cycle = cycle(new ArrayList<>(List.of(model.type())), model.name() + "." + field.name(), nested);
        if (cycle != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3007, cycle);
            joinable = false;
        }
        return joinable ? nested : null;
    }

    /** The entity a {@code @Join}'s attribute reaches, or {@code null} with {@code MQ3001} or {@code MQ3003}. */
    private TypeElement checkAssociation(
            ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        String attribute = field.join().attribute();
        String root = model.root().getSimpleName().toString();
        if (attribute.contains(".")) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3003, where + "'" + attribute
                    + "' is a path; @Join takes a to-one association of " + root + " itself");
            return null;
        }
        Resolution resolution = metamodel.resolve(model.root(), attribute);
        if (resolution.attribute() == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + resolution.problem());
            return null;
        }
        return switch (resolution.attribute().kind()) {
            case TO_ONE -> (TypeElement) ((DeclaredType) resolution.attribute().type()).asElement();
            case COLLECTION -> {
                diagnostics.error(field.element(), DiagnosticCode.MQ3003, where + "'" + attribute + "' on " + root
                        + " is a collection, which a @Join can't select; filter on it with Filters.exists");
                yield null;
            }
            default -> {
                diagnostics.error(field.element(), DiagnosticCode.MQ3003,
                        where + "'" + attribute + "' on " + root + " is not a to-one association");
                yield null;
            }
        };
    }

    /**
     * The chain of joins that leads from {@code chain} through {@code nested} back to a model already on the way,
     * as {@code MQ3007} shows it, or {@code null} when there is none.
     */
    private String cycle(List<TypeElement> visiting, String chain, ModelDefinition nested) {
        if (visiting.contains(nested.type())) {
            return chain + " → " + nested.name();
        }
        visiting.add(nested.type());
        for (ModelField join : nested.joins()) {
            ModelDefinition below = nestedModels.of(join);
            String cycle = below == null
                    ? null : cycle(visiting, chain + " → " + nested.name() + "." + join.name(), below);
            if (cycle != null) {
                return cycle;
            }
        }
        visiting.remove(visiting.size() - 1);
        return null;
    }

    /** {@code MQ3015} for a join one of whose constants is reserved, or already generated for an earlier join. */
    private static void claimJoined(List<JoinedTable> tables, Map<String, String> constants, Set<String> reserved,
            String where, Diagnostics diagnostics) {
        for (JoinedTable table : tables) {
            var generated = new LinkedHashMap<String, String>();
            generated.put(table.table(), "the join " + table.path());
            generated.put(table.prefix(), "the columns of " + table.path());
            if (table.parent() == null) {
                // A @Join of the model itself also has its JoinField (R-FCH-07).
                generated.put(table.prefix() + "_JOIN", "the join field of " + table.path());
            }
            table.columns().forEach(column -> generated.put(column.constant(), column.path()));
            // A join whose prefix is taken clashes on every constant: the first one says it all.
            boolean reported = false;
            for (var constant : generated.entrySet()) {
                String earlier = constants.putIfAbsent(constant.getKey(), constant.getValue());
                String problem = reserved.contains(constant.getKey()) ? " is reserved by the generated class"
                        : earlier != null ? " is also generated for " + earlier : null;
                if (problem != null && !reported) {
                    diagnostics.error(table.join().element(), DiagnosticCode.MQ3015, where + "constant "
                            + constant.getKey() + problem + "; rename the field or set @Join(prefix)");
                    reported = true;
                }
            }
        }
    }

    /** {@code MQ3008} for a class the mapper cannot create, {@code MQ3010} for a record it cannot construct. */
    private static void checkShape(ModelDefinition model, Diagnostics diagnostics) {
        if (model.isRecord()) {
            if (!model.type().getTypeParameters().isEmpty()) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3010, model.name()
                        + ": a generic record can't be mapped through its canonical constructor; "
                        + "remove the type parameters");
            }
            return;
        }
        // Lombok may run after this processor, and its constructor is then not there to see: javac checks the
        // generated call instead, as it does a setter (R-GEN-11).
        boolean lombok = model.type().getAnnotationMirrors().stream()
                .anyMatch(mirror -> ((TypeElement) mirror.getAnnotationType().asElement())
                        .getQualifiedName().contentEquals(LOMBOK_NO_ARGS));
        if (lombok) {
            return;
        }
        // The QModel sits in the model's package, so any constructor that is not private is visible to it.
        boolean creatable = ElementFilter.constructorsIn(model.type().getEnclosedElements()).stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty()
                        && !constructor.getModifiers().contains(Modifier.PRIVATE));
        if (!creatable) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3008,
                    model.name() + ": needs a no-arg constructor for setter mapping");
        }
    }

    /**
     * {@code MQ3001} for a path that does not resolve, {@code MQ3002} for one of another type than the field that no
     * built-in converter bridges, {@code MQ3014} for a converter that does not bridge the two, {@code MQ3016} for a
     * whole entity.
     */
    private void checkAttribute(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        // A model rooted at a source reads the source's fields by name: the name first, then the type (R-SRC-10).
        if (model.readsSource()) {
            checkSourceAttribute(model, field, where, diagnostics);
            return;
        }
        Resolution resolution = metamodel.resolve(model.root(), field.attribute());
        if (!model.readModel() && !writeChecks.checkColumn(model, field, resolution, where, diagnostics)) {
            return;
        }
        if (resolution.attribute() == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + resolution.problem());
            return;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "model type " + display(field.type())
                    + ", entity attribute '" + attribute.name()
                    + "' is a collection, which a column can't select; filter on it with Filters.exists");
            return;
        }
        if (model.readModel() && metamodel.byId(field, attribute)) {
            checkIdForm(model, field, attribute, where, diagnostics);
            return;
        }
        if (field.converter() != null) {
            if (!checkConverter(
                    model, field.element(), field.converter(), field.type(), attribute, where, diagnostics)) {
                return;
            }
        } else if (!types.isSameType(boxed(field.type()), boxed(attribute.type()))
                && builtIns.between(field.type(), attribute.type()) == null) {
            // The engine compares the two boxed types for identity at first use (MQ1001), so assignable is not enough.
            String modelType = display(field.type());
            String attributeType = display(attribute.type());
            if (modelType.equals(attributeType)) {
                modelType = field.type().toString();
                attributeType = attribute.type().toString();
            }
            diagnostics.error(field.element(), DiagnosticCode.MQ3002,
                    where + "model type " + modelType + ", entity attribute type " + attributeType);
            return;
        }
        // A converted column holds what its converter makes of the entity, not the entity.
        if (attribute.kind() == EntityAttribute.Kind.TO_ONE && field.converter() == null) {
            String entity = display(attribute.type());
            String idForm = idFormHint(field, attribute);
            if (field.groupBy()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3028, where + "@GroupBy on a whole " + entity
                        + " entity: the database groups by its foreign key but the select lists every column of the "
                        + "entity" + (idForm == null ? "" : "; group by its id with " + idForm));
                return;
            }
            diagnostics.warning(field.element(), DiagnosticCode.MQ3016, where + "selects the whole " + entity
                    + " entity; use @Join with a query model of " + entity + " to select only its columns"
                    + (idForm == null ? "" : ", or " + idForm + " to read its id"));
        }
    }

    /**
     * The id form of the to-one {@code field} reads, as {@code @Column(attribute = "customer") Long customerId}; or
     * {@code null} when its mapping or target cannot be read by id.
     */
    private String idFormHint(ModelField field, EntityAttribute attribute) {
        TypeMirror id = idFormType(field, attribute);
        return id == null ? null : "@Column(attribute = \"" + field.attribute() + "\") " + display(boxed(id)) + " "
                + field.name() + "Id";
    }

    /** The target's id type when {@code attribute} can be read by its id from {@code field}, else {@code null}. */
    private TypeMirror idFormType(ModelField field, EntityAttribute attribute) {
        if (idFormRefusal(field, attribute) != null
                || metamodel.restriction((TypeElement) attribute.target().asElement()) != null) {
            return null;
        }
        return metamodel.id((TypeElement) attribute.target().asElement()).type();
    }

    /** Why {@code attribute}, a to-one of the root or of an embedded value, can't be read by its id; else null. */
    private String idFormRefusal(ModelField field, EntityAttribute attribute) {
        if (field.attribute().contains(".")) {
            return "it is inside an embedded value";
        }
        if (attribute.idFormRefusal() != null) {
            return attribute.idFormRefusal();
        }
        if (attribute.target() == null) {
            return "its target entity is not visible to the processor";
        }
        String target = metamodel.idFormRefusal((TypeElement) attribute.target().asElement());
        if (target == null && metamodel.id((TypeElement) attribute.target().asElement()).type() == null) {
            return "its target's id type is not visible to the processor";
        }
        return target;
    }

    /**
     * {@code MQ3002} for a to-one column of the target's id (R-PROC-33, D-136) whose mapping or target can't be read
     * by id, or whose type is not the target's id; {@code MQ3014} for a converter that does not bridge the id.
     */
    private void checkIdForm(
            ModelDefinition model, ModelField field, EntityAttribute attribute, String where,
            Diagnostics diagnostics) {
        String refusal = idFormRefusal(field, attribute);
        if (refusal != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "'" + field.attribute()
                    + "' can't be read by its id: " + refusal);
            return;
        }
        String restriction = metamodel.restriction((TypeElement) attribute.target().asElement());
        if (restriction != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3029, where + "'" + field.attribute()
                    + "' can't be read by its id: " + display(attribute.type()) + " has " + restriction
                    + ", so a read takes a null id from a restricted row while the foreign key holds a value");
            return;
        }
        TypeMirror id = metamodel.id((TypeElement) attribute.target().asElement()).type();
        String target = display(attribute.type());
        if (field.converter() != null) {
            var idAttribute = new EntityAttribute(attribute.name(), id, EntityAttribute.Kind.BASIC, null, false,
                    true, true, null, false, null, false, false, null, null);
            checkConverter(model, field.element(), field.converter(), field.type(), idAttribute, where, diagnostics);
        } else if (!types.isSameType(boxed(field.type()), boxed(id))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "model type " + display(field.type())
                    + ", entity attribute type " + target + "; a column of a to-one takes " + target + "'s id type "
                    + display(boxed(id)) + " to read its foreign key");
        }
    }

    /**
     * {@code MQ3014} unless {@code converterClass} is a {@code ColumnConverter} to the attribute's type that the
     * QModel can obtain, and one from {@code modelType} when a field holds the value; a filter column has no field,
     * so it passes {@code null} and takes the converter's model type (R-PROC-07, R-PROC-10).
     */
    private boolean checkConverter(
            ModelDefinition model, Element element, TypeMirror converterClass, TypeMirror modelType,
            EntityAttribute attribute, String where, Diagnostics diagnostics) {
        String name = display(converterClass);
        ConverterType converter = ConverterType.of(types, converterClass);
        if (converter == null) {
            diagnostics.error(element, DiagnosticCode.MQ3014, where + name + " is not a ColumnConverter<"
                    + (modelType == null ? "?" : display(boxed(modelType))) + ", "
                    + display(boxed(attribute.type())) + ">");
            return false;
        }
        boolean fits = true;
        var expected = new ArrayList<String>();
        if (modelType != null && !types.isSameType(converter.model(), boxed(modelType))) {
            expected.add("model type " + display(modelType));
        }
        if (!types.isSameType(converter.attribute(), boxed(attribute.type()))) {
            expected.add("entity attribute type " + display(attribute.type()));
        }
        if (!expected.isEmpty()) {
            diagnostics.error(element, DiagnosticCode.MQ3014, where + name + " converts "
                    + display(converter.model()) + " to " + display(converter.attribute()) + ", "
                    + String.join(", ", expected));
            fits = false;
        }
        if (!converter.isVisibleTo(model.packageName())) {
            diagnostics.error(element, DiagnosticCode.MQ3014, where + name + " is not visible to "
                    + model.generatedName() + ", which is generated in " + model.packageName() + "; make it public");
            fits = false;
        } else if (!converter.hasInstance() && !converter.hasVisibleConstructor(model.packageName())) {
            diagnostics.error(element, DiagnosticCode.MQ3014, where + name
                    + " needs a public static INSTANCE, typed as an OrderedColumnConverter when it is one, or a no-arg "
                    + "constructor visible to " + model.generatedName());
            fits = false;
        }
        return fits;
    }

    private TypeMirror boxed(TypeMirror type) {
        return ProcessorTypes.boxed(types, type);
    }

    /** {@code type} as a message shows it: simple names, with type arguments. */
    static String display(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            var declared = (DeclaredType) type;
            String name = declared.asElement().getSimpleName().toString();
            return declared.getTypeArguments().isEmpty() ? name : name + declared.getTypeArguments().stream()
                    .map(ModelValidator::display).collect(Collectors.joining(", ", "<", ">"));
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return display(((ArrayType) type).getComponentType()) + "[]";
        }
        return type.toString();
    }
}
