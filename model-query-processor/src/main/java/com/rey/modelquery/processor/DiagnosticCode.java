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
    MQ3016("Column selects a whole entity; a warning");

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
