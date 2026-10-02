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

    /** A {@code @Join} attribute is not a to-one association, or not one to the nested model's root. */
    MQ3003("@Join attribute is not a to-one association to the nested model's root"),

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
    MQ3016("Column selects a whole entity; a warning"),

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

    /** An update-model field maps through an association, or to a collection. */
    MQ3301("Update-model field maps through a join or a collection"),

    /** {@code @Join}, {@code @Aggregate} or {@code @GroupBy} on an update model, which only writes root columns. */
    MQ3302("@Join, @Aggregate or @GroupBy on an update model"),

    /** An update-model field writes the id without {@code @PrimaryKey}, or writes the {@code @Version}. */
    MQ3303("Update-model field maps to the primary key without @PrimaryKey, or to the @Version attribute"),

    /** An update-model field maps to an attribute that is {@code updatable = false} or the inverse of a to-one. */
    MQ3304("Update-model field maps to an attribute that can't be written"),

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
    MQ3406("@Child through path, key or child model that a through child can't load");

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
