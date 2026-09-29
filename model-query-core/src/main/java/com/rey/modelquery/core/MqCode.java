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
    MQ1102("on(...) requires an alias; add as(...)"),

    /** Two {@code Agg.of} fields share a name with different expressions (R-AGG-02). */
    MQ1103("Two Agg.of fields share a name with different expressions"),

    /** {@code keyset()} or {@code primaryKeyFirst(...)} without a primary key (R-QRY-03). */
    MQ1201("keyset() and primaryKeyFirst(...) require a primary key"),

    /** {@code build()} called without {@code columns(...)} (R-QRY-02). */
    MQ1202("columns(...) is required"),

    /** A value-form filter received {@code null} (api/12 §1). */
    MQ1301("A value-form filter received null; pass Optional.empty() to skip the filter"),

    /** A column inside {@code exists(...)} is not on or below the given path (R-FLT-11). */
    MQ1302("A column inside exists(...) is not on or below the given path"),

    /** A selected non-aggregate column is not in the group-by (R-AGG-08). */
    MQ1401("A selected non-aggregate column is not in the group-by"),

    /** Keyset paging or primary-key-first on a grouped query (R-AGG-10). */
    MQ1402("keyset() and primaryKeyFirst(...) are refused on a grouped query"),

    /** {@code Agg.sum} or {@code Agg.sumAsLong} over a column whose SQL sum type is not its result type (R-AGG-03). */
    MQ1403("Agg.sum or Agg.sumAsLong over a column whose SQL sum type differs from the declared result type");

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
