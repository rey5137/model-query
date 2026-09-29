package com.rey.modelquery.core;

/**
 * Registry of {@code MQnnnn} codes raised by the library (spec reference/90). A code's meaning is fixed once released
 * (INV-10); each constant carries its default message.
 *
 * @implSpec R-ERR-01
 */
@Incubating
public enum MqCode {

    /** A column's declared type does not match the entity attribute (R-COL-08). */
    MQ1001("A column's declared type does not match the entity attribute"),

    /** Two {@code TableField}s share a join key but carry different {@code on(...)} conditions (R-COL-04). */
    MQ1101("Two table fields share a join key but carry different on(...) conditions"),

    /** {@code on(...)} used without {@code as(...)} (R-COL-04). */
    MQ1102("on(...) requires an alias; add as(...)");

    private final String defaultMessage;

    MqCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /** The wire form of the code, for example {@code MQ1101}. */
    public String code() {
        return name();
    }

    /** The message used when the raising site adds no detail. */
    public String defaultMessage() {
        return defaultMessage;
    }
}
