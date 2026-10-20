package com.rey.modelquery.processor;

/**
 * The {@code MQ3xxx} codes the processor reports (spec processor/32 §1). A code's meaning is fixed once released
 * (INV-10); each constant carries its default message.
 */
enum DiagnosticCode {

    /** A column names an attribute its entity does not have. */
    MQ3001("Unknown attribute"),

    /** A column's model type is not the entity attribute's type. */
    MQ3002("Model type does not match the entity attribute type"),

    /** A {@code @Join} is not to-one, or its nested root or treat target is unrelated to the association target. */
    MQ3003("@Join needs a to-one target related to the nested root and its treat targets"),

    /** A model without aggregates has no {@code @PrimaryKey}. */
    MQ3004("Missing @PrimaryKey"),

    /** A {@code @Join} field is not an {@code Optional} of a {@code @QueryModel} in the same compilation. */
    MQ3005("@Join field is not an Optional of a @QueryModel in the same compilation"),

    /** A nested model has no {@code @PrimaryKey} to decide its presence by. */
    MQ3006("Nested model has no @PrimaryKey"),

    /** Nested models join each other in a cycle. */
    MQ3007("Join cycle between nested models"),

    /** A class model has no no-arg constructor visible from its package. */
    MQ3008("Class model has no visible no-arg constructor"),

    /** A record component is primitive and not a {@code @PrimaryKey}. */
    MQ3009("Record component is primitive and not a @PrimaryKey"),

    /** A record model cannot be built through a canonical constructor of known types. */
    MQ3010("Record model is generic or has no usable canonical constructor"),

    /** A {@code @FilterColumn} path does not resolve, or crosses a collection with no explicit join type. */
    MQ3011("@FilterColumn path does not resolve, or crosses a collection with no explicit joinType"),

    /** Two {@code @FilterColumn}s share an alias and a path prefix, and join it differently. */
    MQ3012("@FilterColumns with the same alias and path prefix have different joinTypes"),

    /** A {@code @FilterColumn} takes the name of another generated constant. */
    MQ3013("@FilterColumn name clashes with a generated constant"),

    /** A converter is not a {@code ColumnConverter} between the field and the attribute, or cannot be obtained. */
    MQ3014("Converter does not fit the column, or has no INSTANCE and no visible no-arg constructor"),

    /** Two generated constants share a name, or one takes a reserved name. */
    MQ3015("Generated constant name is already taken"),

    /** A column selects a whole to-one entity; a warning that names the id form. */
    MQ3016("Column selects a whole entity; a warning"),

    /** A model's {@code root}, or the {@code root} of a model it nests, still names no class in the last round. */
    MQ3017("Model root does not resolve to a class"),

    /** A {@code @Computed} or {@code @Aggregate(expression)} class is no usable {@code ExpressionDefinition}. */
    MQ3018("@Computed or @Aggregate(expression) class is not a usable ExpressionDefinition"),

    /** {@code @Computed} is combined with an annotation it can't share a field with, or is on a primitive field. */
    MQ3019("@Computed combined with an annotation it can't share a field with, or on a primitive field"),

    /** A {@code @Selected} field is not exactly {@code SelectSet<Model>}, or a model has two of them. */
    MQ3020("@Selected field is not a SelectSet of the model itself, or the model has two"),

    /** {@code @Selected} is combined with another annotation of the library on its field. */
    MQ3021("@Selected combined with another field annotation"),

    /**
     * A {@code @FilterColumn} whose key in {@code fields()} a mapped column, a {@code @Computed} field or another
     * filter column already holds, so it is left out of the index; a warning.
     */
    MQ3022("Filter column left out of fields() because its key is already held; a warning"),

    /**
     * A model bound twice for one kind, both bound and annotated with a model annotation, or a binding that would
     * generate one class name with another binding, of any model, or with an annotated model of the compilation
     * (R-PROC-28, D-141).
     */
    MQ3023("Model bound twice for one kind, both bound and annotated, or bound to a generated name already taken"),

    /** A bound model, its no-arg constructor, or a setter or getter its QModel calls is not public (R-GEN-35). */
    MQ3024("Bound model, its constructor or its accessors not public"),

    /**
     * A {@code @Computed} that names no definition and takes none from a {@code @BindExpression}, or a
     * {@code @BindExpression} that matches no valueless {@code @Computed} or {@code @Aggregate} (R-PROC-29).
     */
    MQ3025("Expression with no definition, or @BindExpression matching nothing"),

    /**
     * A model a bound model nests through {@code @Join} or holds through {@code @Child} is neither annotated
     * {@code @QueryModel} nor bound by a {@code @BindQueryModel} of the compilation (R-PROC-31).
     */
    MQ3026("Nested model of a bound model neither annotated nor bound"),

    /** {@code modelquery.jspecify} is on, but JSpecify is not on the compile classpath; no annotation is generated. */
    MQ3027("modelquery.jspecify is on but JSpecify is not on the classpath"),

    /** {@code @GroupBy} on a column that selects a whole to-one entity; group by the foreign key's id instead. */
    MQ3028("@GroupBy on a whole-entity to-one column"),

    /**
     * The id form of a to-one whose target has {@code @SQLRestriction} or {@code @Where}: a read could miss the key.
     */
    MQ3029("Id form over a target with a Hibernate restriction"),

    /** A {@code @Child} on a source model, which is one statement and has no parent query to fill it. */
    MQ3030("@Child on a source model"),

    /** A source model's key is not unique per row: not the root id or the full group set, a nullable group key, or a
     * to-many read. */
    MQ3031("Source model key is not the root id, the inner source key fields or the full non-nullable group set"),

    /** A {@code @Window} is not well written: a bad path or type, no {@code orderBy} where one is needed, a missing
     * or misplaced {@code frame}, or a grouped-source term that is neither a group key nor an aggregate. */
    MQ3032("@Window is not well written"),

    /** {@code @Window} on a model that is not a {@code @SourceModel}. */
    MQ3033("@Window is only allowed on a @SourceModel"),

    /** A branch's fields do not match its shape's, or a {@code @Discriminator} is not usable (R-PROC-38). */
    MQ3034("A branch does not map its shape, or a @Discriminator is misplaced"),

    /** {@code @Join} on a model rooted at a source, which has no associations. */
    MQ3035("@Join on a model rooted at a source"),

    /** An outer model's {@code @PrimaryKey} is not exactly its source's key. */
    MQ3036("@PrimaryKey of an outer model is not its source's key"),

    /** A recursive source model is not well written: a {@code @Depth} that is not one {@code Integer}, one outside
     * a {@code @SourceModel}, or {@code @Window}, {@code @Aggregate} or {@code @GroupBy} beside it (R-PROC-37). */
    MQ3037("A recursive source takes one Integer @Depth, and no window, aggregate or group by"),

    /** A model's {@code root} is neither an {@code @Entity} nor a {@code @SourceModel}, or both. */
    MQ3038("Root is neither an @Entity nor a @SourceModel, or both"),

    /** A {@code @Child} whose child model is rooted at a source, which no fetch plan can pass a source to. */
    MQ3039("@Child whose child model is rooted at a source"),

    /** {@code @QueryModel(generateChanges)} on a model rooted at a source: a write needs an entity root (R-GEN-37,
     * R-PROC-39). */
    MQ3040("generateChanges on a model rooted at a source"),

    /** A source over a source is recursive, a branch or a shape, or roots at a branch model (R-PROC-40). */
    MQ3041("A source over a source cannot be recursive, a branch, a shape or rooted at a branch"),

    /** A {@code treat} member is invalid, redundant, or part of a key, source-rooted model or write. */
    MQ3042("Invalid treat declaration"),

    /** A {@code @Subtype} is repeated, has another type or annotation, or is source-rooted or written. */
    MQ3043("Invalid @Subtype declaration"),

    /** An {@code @Aggregate} field is primitive, though an aggregate can be {@code NULL}. */
    MQ3201("@Aggregate field is primitive"),

    /** An {@code @Aggregate} field's type is not the function's result type. */
    MQ3202("@Aggregate field type does not match the function's result type"),

    /** A model with {@code @Aggregate} fields has no {@code @GroupBy} field and is not {@code singleGroup}. */
    MQ3203("@Aggregate model has no @GroupBy field and is not singleGroup"),

    /**
     * {@code @GroupBy} is combined with {@code @Aggregate} or {@code @Join}, or {@code @Aggregate} with
     * {@code @PrimaryKey}, {@code @Column}, {@code @Join} or {@code @Transient}.
     */
    MQ3204("@GroupBy or @Aggregate combined with an annotation it can't share a field with"),

    /** {@code SUM} over a 32-bit attribute, which the database returns as a {@code Long}. */
    MQ3205("@Aggregate(fn = SUM) over a 32-bit attribute"),

    /** {@code @Aggregate(distinct = true)} on a function other than {@code COUNT}. */
    MQ3206("@Aggregate(distinct = true) on a function other than COUNT"),

    /** {@code @QueryModel(singleGroup = true)} on a model that has {@code @GroupBy} fields. */
    MQ3207("singleGroup combined with @GroupBy fields"),

    /** {@code @Aggregate} with both {@code attribute} and {@code expression}. */
    MQ3208("@Aggregate takes attribute or expression, not both"),

    /** An update- or insert-model field maps through an association, or to a collection. */
    MQ3301("Update- or insert-model field maps through a join or a collection"),

    /**
     * {@code @Join}, {@code @Aggregate}, {@code @GroupBy}, {@code @Computed} or {@code @Selected} on an update model,
     * which only writes root columns.
     */
    MQ3302("@Join, @Aggregate, @GroupBy, @Computed or @Selected on an update model"),

    /**
     * An update-model field writes the id without {@code @PrimaryKey}, or an update- or insert-model field writes the
     * {@code @Version}.
     */
    MQ3303("Update-model field maps to the primary key without @PrimaryKey, or a write model's to the @Version"),

    /**
     * An update-model field maps to an attribute that is {@code updatable = false}, an insert-model field to one that
     * is {@code insertable = false}, or an update- or insert-model field to the inverse of a to-one.
     */
    MQ3304("Update- or insert-model field maps to an attribute that can't be written"),

    /** A to-one written by id from a field whose type is not the target's id type. */
    MQ3305("To-one attribute written by id with the wrong id type"),

    /** The {@code @PrimaryKey} of an update model, or of a query model with {@code generateChanges}, is not the id. */
    MQ3306("@PrimaryKey of an update model or a generateChanges query model is not the root entity's id"),

    /** A field whose change-set members clash with those of {@code Changes<M>}. */
    MQ3307("Update-model field generates a change-set member that clashes with Changes"),
    /**
     * {@code @Child} on a field that is not a {@code List} or {@code Optional} of a {@code @QueryModel}, beside
     * {@code @Join}, {@code @Transient}, {@code @Aggregate} or {@code @GroupBy}, or on an update model.
     */
    MQ3401("@Child field is not a List or Optional of a @QueryModel, or carries an annotation it can't share"),
    /** A {@code @Child} {@code key} or {@code foreignKey} names no attribute of its root. */
    MQ3402("@Child key or foreignKey names no attribute of its root"),
    /** A {@code @Child}'s key and foreign-key attributes are of different types. */
    MQ3403("@Child key and foreignKey attribute types differ"),
    /** A {@code @Child} key of several attributes, an embedded value, or an array. */
    MQ3404("@Child with a composite or array-typed key"),
    /**
     * A {@code List} {@code @Child} without {@code foreignKey} (unless {@code through}), or whose model has no
     * {@code @PrimaryKey}.
     */
    MQ3405("List @Child without foreignKey, or whose model has no @PrimaryKey"),
    /**
     * A {@code @Child} {@code through} path that is blank, crosses a non-association or an embedded value, or ends at
     * another type than the child's root; a {@code key} that is not the parent root's single {@code @Id}; or a grouped
     * child model.
     */
    MQ3406("@Child through path, key or child model that a through child can't load"),

    /**
     * An insert model that names its root's generated id, or does not name with {@code @PrimaryKey} an id that has
     * no {@code @GeneratedValue}, or whose {@code @PrimaryKey} is not the id; a {@code generateInserts} query model
     * whose {@code @PrimaryKey} doesn't name all of an assigned id.
     */
    MQ3501("Insert model's @PrimaryKey does not match the root entity's id and its generator"),
    /**
     * {@code @Join}, {@code @FilterColumn}, {@code @Aggregate}, {@code @GroupBy}, {@code @Computed}, {@code @Child}
     * or {@code @Selected} on an insert model, which reads no row of its root, or {@code @Transient}, since every
     * field is a column.
     */
    MQ3502("Annotation not allowed on an insert model"),
    /** A type carrying more than one of {@code @QueryModel}, {@code @UpdateModel} and {@code @InsertModel}. */
    MQ3503("More than one model annotation on one type"),
    /**
     * An insert model, or a {@code generateInserts} query model, whose root shows the processor no id type, so its
     * keys are typed {@code Object}; a warning.
     */
    MQ3504("Insert model's root has no id type the processor can see; a warning"),
    /** {@code generateInserts} on a grouped model, or on one with no root column it can write. */
    MQ3505("generateInserts needs an ungrouped model with a writable root column"),
    /**
     * {@code @ExcludeFromInserts} on a field of a model that doesn't generate inserts, or on a field
     * {@code generateInserts} already leaves out.
     */
    MQ3506("@ExcludeFromInserts that does nothing or is not allowed here");

    private final String defaultMessage;

    DiagnosticCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /** The code as it appears in a diagnostic, {@code MQ3001}. */
    String code() {
        return name();
    }

    /** What the code means, independent of the model it is reported on. */
    String defaultMessage() {
        return defaultMessage;
    }
}
