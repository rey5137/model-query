package com.rey.modelquery.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * A {@code @QueryModel}, {@code @UpdateModel} or {@code @InsertModel} type as written, before any check: what the
 * validation and emission steps both read.
 *
 * @param type the model class or record
 * @param root the entity named by {@code @QueryModel(root)}
 * @param generatedName the simple name of the QModel class, in {@link #packageName()}
 * @param selectSets whether {@code ALL} and {@code DEFAULT} are generated
 * @param singleGroup whether {@code @QueryModel(singleGroup)} declares aggregates with no {@code @GroupBy}
 * @param fields the model's fields or record components, in declaration order
 * @param filterColumns the model's {@code @FilterColumn}s, in declaration order
 * @param kind which of the three model annotations the type carries
 * @param generateChanges whether {@code @QueryModel(generateChanges)} asks for a change set
 * @param generateInserts whether {@code @QueryModel(generateInserts)} asks for the insert members (R-PROC-26)
 * @param origin what the generated files originate from: the model's type, or the type or {@code package-info} of a
 *     binding, whose package they are generated in (R-GEN-35, D-140)
 * @param source the source model {@code root} names, when it is a {@code @SourceModel}, else {@code null}
 *     (R-PROC-39)
 * @param shape the source model this one is a branch of, named by {@code @SourceModel(shape)}, else {@code null}
 *     (R-PROC-38)
 */
record ModelDefinition(
        TypeElement type, TypeElement root, String generatedName, boolean selectSets, boolean singleGroup,
        List<ModelField> fields, List<FilterColumnDefinition> filterColumns, Kind kind,
        boolean generateChanges, boolean generateInserts, Element origin, ModelDefinition source,
        ModelDefinition shape, List<JoinSourceDefinition> joinSources) {

    ModelDefinition {
        fields = List.copyOf(fields);
        joinSources = List.copyOf(joinSources);
        filterColumns = List.copyOf(filterColumns);
    }

    /** Whether the type is a {@code @QueryModel}. */
    boolean queryModel() {
        return kind == Kind.QUERY;
    }

    /**
     * Whether the model is read into: a {@code @QueryModel} or a {@code @SourceModel}. Both carry the same columns,
     * select sets, {@code KEY} and {@code fields()}; a source declares {@code source()} in place of {@code query()}
     * and no mapper and no write member (R-PROC-35, R-GEN-37).
     */
    boolean readModel() {
        return kind == Kind.QUERY || kind == Kind.SOURCE;
    }

    /** Whether the type is a {@code @SourceModel}: a query an outer model reads by field name (R-PROC-35). */
    boolean sourceModel() {
        return kind == Kind.SOURCE;
    }

    /**
     * Whether this model's {@code root} is a {@code @SourceModel}, so the model reads a source rather than an entity
     * (R-PROC-39). {@link #source()} is the source model then, and {@code null} otherwise.
     */
    boolean readsSource() {
        return source != null;
    }

    /**
     * Whether this source model is a <em>branch</em>: it names its shape through {@code @SourceModel(shape)}, so its
     * fields are checked against the shape's and its {@code source()} builds a {@code SourceQuery<Shape>} (R-PROC-38).
     */
    boolean branch() {
        return shape != null;
    }

    /**
     * Whether this source model is <em>recursive</em>: it declares a {@code @Depth} field, so each of its rows carries
     * the level the CTE assigned it and its builder requires the recursive step (R-PROC-37, R-SRC-07).
     */
    boolean recursive() {
        return !depths().isEmpty();
    }

    /** Whether the type is an {@code @UpdateModel}, which is only read by the processor and never instantiated. */
    boolean updateModel() {
        return kind == Kind.UPDATE;
    }

    /** Whether the type is an {@code @InsertModel}, whose instances are the rows an insert writes (R-PROC-23). */
    boolean insertModel() {
        return kind == Kind.INSERT;
    }

    /** The package the QModel and change set are generated in: the model's, or a binding's (R-GEN-35). */
    String packageName() {
        Element element = origin;
        while (element.getKind() != ElementKind.PACKAGE) {
            element = element.getEnclosingElement();
        }
        return ((PackageElement) element).getQualifiedName().toString();
    }

    boolean isRecord() {
        return type.getKind() == ElementKind.RECORD;
    }

    /** The model's simple name, as diagnostics name it. */
    String name() {
        return type.getSimpleName().toString();
    }

    /** The fields that are columns, in declaration order. */
    List<ModelField> columns() {
        return fields.stream().filter(ModelField::column).toList();
    }

    /** The fields carrying {@code @Computed}, in declaration order. */
    List<ModelField> computed() {
        return fields.stream().filter(ModelField::computed).toList();
    }

    /**
     * The fields a {@code SelectSet} can hold, in the order they are emitted: the columns, then the computed
     * expressions, which are declared after every column constant (R-GEN-27).
     */
    List<ModelField> selections() {
        var all = new ArrayList<>(columns());
        all.addAll(computed());
        return all;
    }

    /**
     * A source model's {@code ALL}: every field of the model, the aggregates and window fields included, because
     * {@code ALL} is the projection of the source's CTE body, which carries every field an outer model reads by name,
     * so R-PROC-17's reason for leaving aggregates out — that every query of the model would be grouped — does not
     * apply to a source (`api/17` R-SRC-10, R-SRC-12, R-GEN-37, R-GEN-38). A query model's {@code ALL} is
     * {@link #selections()}.
     */
    List<ModelField> sourceFields() {
        var all = new ArrayList<>(selections());
        all.addAll(aggregates());
        all.addAll(windows());
        return all;
    }

    /** Whether a change set is generated: always for an update model, on request for a query model (R-GEN-21). */
    boolean changes() {
        return !readsSource() && (updateModel() || generateChanges);
    }

    /** Whether {@code INSERT_COLUMNS} and the insert builders are generated: on an insert model, or on request. */
    boolean inserts() {
        return !readsSource() && (insertModel() || generateInserts);
    }

    /** Whether the model has groups: {@code @Aggregate} or {@code @GroupBy} fields, or {@code singleGroup}. */
    boolean grouped() {
        return singleGroup || fields.stream().anyMatch(field -> field.aggregate() != null || field.groupBy());
    }

    /** The change set's simple name, in {@link #packageName()}: {@code OrderPatchChanges}. */
    String changesName() {
        return name() + "Changes";
    }

    /**
     * The columns a change set writes, in declaration order: the non-key columns, every one of which is on the root,
     * since a column's path never crosses an association (R-GEN-19, R-PROC-19).
     */
    List<ModelField> writable() {
        return fields.stream().filter(field -> field.column() && !field.primaryKey() && field.sourceColumn() == null)
                .toList();
    }

    /** The {@code @Join} fields, in declaration order. */
    List<ModelField> joins() {
        return fields.stream().filter(field -> field.join() != null).toList();
    }

    /** The {@code @Child} fields, in declaration order. */
    List<ModelField> children() {
        return fields.stream().filter(field -> field.child() != null).toList();
    }

    /** The {@code @Aggregate} fields, in declaration order. */
    List<ModelField> aggregates() {
        return fields.stream().filter(field -> field.aggregate() != null).toList();
    }

    /** The {@code @Window} fields, in declaration order (R-GEN-38). */
    List<ModelField> windows() {
        return fields.stream().filter(ModelField::windowed).toList();
    }

    /** The {@code @Discriminator} fields, in declaration order: one per shape, in a model whose key carries one. */
    List<ModelField> discriminators() {
        return fields.stream().filter(ModelField::discriminated).toList();
    }

    /**
     * The {@code @GroupBy} columns or computed expressions, in declaration order: the members of {@code GROUP_KEYS}.
     */
    List<ModelField> groupKeys() {
        return fields.stream().filter(field -> (field.column() || field.computed()) && field.groupBy()).toList();
    }

    /** The fields carrying {@code @Selected}, in declaration order: one in a valid model (R-PROC-25). */
    List<ModelField> selected() {
        return fields.stream().filter(ModelField::selected).toList();
    }

    /**
     * Whether the generated class holds the set of every field its mapper reads, which a query model with a
     * {@code @Selected} field fills (R-GEN-29).
     */
    boolean hasSelected() {
        return readModel() && fields.stream().anyMatch(ModelField::selected);
    }

    /** The {@code @PrimaryKey} columns, in declaration order. */
    List<ModelField> keys() {
        return fields.stream().filter(field -> field.column() && field.primaryKey()).toList();
    }

    /**
     * The fields an outer model may read by name, in declaration order: a source's columns, computed expressions,
     * aggregates and window fields, which alone are emitted with {@code named(...)} (R-GEN-37, R-GEN-38). A
     * {@code @FilterColumn} and a {@code @Join} carry no property to alias a CTE column by.
     */
    List<ModelField> readable() {
        return fields.stream()
                .filter(field -> field.column() || field.computed() || field.aggregate() != null
                        || field.windowed())
                .toList();
    }

    /**
     * The field of {@link #source()} an outer model's column reads: the readable source field named by
     * {@code attribute}, which is the CTE column alias the source's own constant carries; {@code null} when the
     * source declares none, or when this model reads no source (R-SRC-10).
     */
    ModelField sourceField(String attribute) {
        return source == null ? null
                : source.readable().stream().filter(field -> field.name().equals(attribute)).findFirst().orElse(null);
    }

    /** The annotation a model type carries, which decides what is generated for it. */
    enum Kind {
        /** {@code @QueryModel}: read into, and written through a change set when it asks for one. */
        QUERY,
        /** {@code @SourceModel}: a query an outer model reads by field name; generates {@code source()}
         * (R-PROC-35). */
        SOURCE,
        /** {@code @UpdateModel}: the columns a bulk update writes, through a change set. */
        UPDATE,
        /** {@code @InsertModel}: the columns an insert writes, read from each row (R-PROC-23). */
        INSERT
    }

    /**
     * One field of a class model, or one component of a record model, read from the record's field of the same name.
     *
     * @param element the field, which diagnostics are reported on
     * @param column {@code false} for a {@code @Transient}, {@code @Join}, {@code @Aggregate} or {@code @Child}
     *     field, which keeps its place in a record's constructor
     * @param attribute the entity attribute path the column reads, dotted through embedded values
     * @param constant the name of the generated column constant
     * @param primaryKey whether the field is a {@code @PrimaryKey}
     * @param excludedFromDefaults whether the field is left out of {@code DEFAULT}
     * @param converter the class named by {@code @Column(converter)}, or {@code null} for none
     * @param definition the class named by {@code @Computed} or by a binding's {@code @BindExpression} for it, the
     *     {@code void} type when neither names one ({@code MQ3025}), or {@code null} when the field carries none
     * @param join what {@code @Join} says of the field, or {@code null} when it carries none
     * @param aggregate what {@code @Aggregate} says of the field, or {@code null} when it carries none
     * @param window what {@code @Window} says of the field, or {@code null} when it carries none
     * @param groupBy whether the field carries {@code @GroupBy}
     * @param child what {@code @Child} says of the field, or {@code null} when it carries none; a {@code @Child}
     *     field has no {@code join} and no {@code aggregate}, whatever else it carries
     * @param selected whether the field carries {@code @Selected}, which makes it no column (R-PROC-25)
     * @param discriminator the value named by {@code @Discriminator}, which fixes the field to a constant, or
     *     {@code null} when the field carries none (R-PROC-38)
     * @param depth whether the field carries {@code @Depth}, which makes the model recursive (R-PROC-37)
     * @param treat the subtype named by {@code @Column(treat)}, or {@code null} for none (R-PROC-41)
     * @param subtype whether the field carries {@code @Subtype} (R-PROC-43)
     * @param sourceColumn what {@code @SourceColumn} says of the field, or {@code null} when it carries none; such a
     *     field is a column over a joined source (R-PROC-44)
     */
    record ModelField(
            VariableElement element, boolean column, String attribute, String constant, boolean primaryKey,
            boolean excludedFromDefaults, TypeMirror converter, TypeMirror definition, JoinDefinition join,
            AggregateDefinition aggregate, WindowDefinition window, boolean groupBy, ChildDefinition child,
            boolean selected, String discriminator, boolean depth, TypeMirror treat, boolean subtype,
            SourceColumnDefinition sourceColumn) {

        String name() {
            return element.getSimpleName().toString();
        }

        TypeMirror type() {
            return element.asType();
        }

        /** Whether the field carries {@code @Computed}. */
        boolean computed() {
            return definition != null;
        }

        /** Whether the field carries {@code @Window}, which makes it a window field and no column (R-GEN-38). */
        boolean windowed() {
            return window != null;
        }

        /** Whether the field carries {@code @Discriminator}, which fixes it to a constant (R-PROC-38). */
        boolean discriminated() {
            return discriminator != null;
        }
    }

    /**
     * A {@code @JoinSource} as written.
     *
     * @param name the join's name
     * @param source the class named by {@code source}
     * @param sourceModel the source model it reads, or {@code null} when the class is no {@code @SourceModel}
     * @param kind {@code LEFT} or {@code INNER}
     * @param on the key pairs, in order
     */
    record JoinSourceDefinition(
            String name, TypeMirror source, ModelDefinition sourceModel, String kind, List<JoinOn> on) {

        JoinSourceDefinition {
            on = List.copyOf(on);
        }
    }

    /**
     * One {@code @JoinSource.On} pair.
     *
     * @param column the outer attribute path
     * @param key the source's key field
     */
    record JoinOn(String column, String key) {}

    /**
     * A {@code @SourceColumn} as written.
     *
     * @param join the join's name
     * @param field the source field's name
     */
    record SourceColumnDefinition(String join, String field) {}

    /** The {@code @Depth} fields, in declaration order: one in a valid recursive model (R-PROC-37). */
    List<ModelField> depths() {
        return fields.stream().filter(ModelField::depth).toList();
    }

    /**
     * A {@code @Join} as written on its field.
     *
     * @param attribute the association on the model's root entity
     * @param type the JPA join type's name, {@code LEFT} or {@code INNER}
     * @param prefix what the join's constants start with: {@code CUSTOMER} for {@code CUSTOMER_TABLE}
     * @param alias the join's alias, the field's name when another {@code @Join} reads the same attribute
     *     (R-PROC-09); {@code ""} for none
     * @param nested {@code X} of a field declared {@code Optional<X>}, or {@code null} for any other type
     * @param treat the subtype that declares the association, or {@code null} for none (R-PROC-41)
     */
    record JoinDefinition(
            String attribute, String type, String prefix, String alias, TypeMirror nested, TypeMirror treat) {}

    /**
     * A {@code @Child} as written on its field.
     *
     * @param key the paths written for {@code key}; empty for the model's {@code @PrimaryKey} attribute
     * @param foreignKey the paths written for {@code foreignKey}; empty for the child's {@code @PrimaryKey} attribute
     * @param through the association path written for {@code through}; {@code ""} for none
     * @param toMany whether the field is a {@code List}, rather than an {@code Optional}
     * @param model {@code X} of a field declared {@code List<X>} or {@code Optional<X>}, or {@code null} for any
     *     other type
     */
    record ChildDefinition(List<String> key, List<String> foreignKey, String through, boolean toMany,
            TypeMirror model) {

        ChildDefinition {
            key = List.copyOf(key);
            foreignKey = List.copyOf(foreignKey);
        }
    }

    /**
     * An {@code @Aggregate} as written on its field.
     *
     * @param fn the function's name: {@code COUNT}, {@code SUM}, {@code AVG}, {@code MIN} or {@code MAX}
     * @param attribute the entity attribute path the function reads; {@code ""} for a {@code COUNT} over the root or
     *     an aggregate over an expression
     * @param expression the class named by {@code expression}, or {@code null} for none
     * @param distinct whether {@code distinct = true} was written
     * @param orderBy the entries written for {@code orderBy}, as written
     * @param filter the class named by {@code filter}, or {@code null} for none
     * @param scale the {@code scale} written, or {@code null} when it was left out
     */
    record AggregateDefinition(String fn, String attribute, TypeMirror expression, boolean distinct,
            List<String> orderBy, TypeMirror filter, Integer scale) {

        AggregateDefinition {
            orderBy = List.copyOf(orderBy);
        }
    }

    /**
     * One {@code FIRST_BY} order key, {@code "path [asc|desc] [nulls first|nulls last]"}.
     *
     * @param path the attribute path
     * @param ascending whether the key sorts ascending
     * @param nulls {@code "first"}, {@code "last"} or {@code ""} for the database's precedence
     */
    record AggregateOrder(String path, boolean ascending, String nulls) {

        /** The key {@code entry} writes, or {@code null} when it is malformed. */
        static AggregateOrder parse(String entry) {
            String[] words = entry.trim().split("\\s+");
            int at = 1;
            boolean ascending = true;
            if (at < words.length && (words[at].equalsIgnoreCase("asc") || words[at].equalsIgnoreCase("desc"))) {
                ascending = words[at++].equalsIgnoreCase("asc");
            }
            String nulls = "";
            if (at + 1 < words.length && words[at].equalsIgnoreCase("nulls")
                    && (words[at + 1].equalsIgnoreCase("first") || words[at + 1].equalsIgnoreCase("last"))) {
                nulls = words[at + 1].toLowerCase(Locale.ROOT);
                at += 2;
            }
            return words[0].isEmpty() || at != words.length ? null : new AggregateOrder(words[0], ascending, nulls);
        }
    }

    /**
     * A {@code @Window} as written on its field.
     *
     * @param function the function's name: {@code ROW_NUMBER}, {@code SUM} and the rest
     * @param argument the attribute path or {@code @Aggregate} field name the function reads; {@code ""} for a
     *     ranking function
     * @param partitionBy the paths written for {@code partitionBy}
     * @param orderBy the order terms written for {@code orderBy}, in order
     * @param frame the frame's name: {@code NONE}, a {@code ROWS_*} or a {@code RANGE_*} constant
     */
    record WindowDefinition(String function, String argument, List<String> partitionBy, List<WindowOrder> orderBy,
            String frame) {

        WindowDefinition {
            partitionBy = List.copyOf(partitionBy);
            orderBy = List.copyOf(orderBy);
        }
    }

    /**
     * One {@code orderBy} term of a {@code @Window}: a path and its direction, ascending when the entry is written
     * without one.
     *
     * @param path the attribute path, or an {@code @Aggregate} field name in a grouped source
     * @param ascending {@code true} for ascending, the default
     */
    record WindowOrder(String path, boolean ascending) {}

    /**
     * A {@code @FilterColumn} as written on the model's type.
     *
     * @param name the name of the generated column constant
     * @param path the dotted attribute path from the root entity
     * @param joinType the JPA join type's name for an association no {@code @Join} joins, {@code LEFT} or
     *     {@code INNER}
     * @param explicitJoinType whether {@code joinType} was written, which a path across a collection needs and
     *     which alone can differ from the type of a {@code @Join} the path reuses
     * @param alias the alias that puts the path on a join of its own; {@code ""} for none
     * @param converter the class named by {@code converter}, or {@code null} for none
     * @param treat the subtype that declares the first path segment, or {@code null} for none (R-PROC-41)
     */
    record FilterColumnDefinition(
            String name, String path, String joinType, boolean explicitJoinType, String alias, TypeMirror converter,
            TypeMirror treat) {

        /** The column as a diagnostic names it, after the model's name: {@code @FilterColumn(CUSTOMER_COUNTRY)}. */
        String label() {
            return "@FilterColumn(" + name + ")";
        }
    }
}
