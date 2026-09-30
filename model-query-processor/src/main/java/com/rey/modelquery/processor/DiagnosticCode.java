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

    /** A model without aggregates has no {@code @PrimaryKey}. */
    MQ3004("Missing @PrimaryKey"),

    /** A class model has no no-arg constructor visible from its package. */
    MQ3008("Class model has no visible no-arg constructor"),

    /** A record component is primitive and not a {@code @PrimaryKey}. */
    MQ3009("Record component is primitive and not a @PrimaryKey"),

    /** A record model cannot be built through a canonical constructor of known types. */
    MQ3010("Record model is generic or has no usable canonical constructor"),

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
