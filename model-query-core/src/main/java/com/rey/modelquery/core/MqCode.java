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

    /** An attribute named by a column or join does not exist on its entity (R-COL-01, R-COL-08). */
    MQ1002("An attribute named by a column or join does not exist"),

    /** A column or table sits on a root entity the query is not rooted at (R-COL-01). */
    MQ1003("A column or table sits on a root entity the query is not rooted at"),

    /** Two {@code TableField}s share a join key but carry different {@code on(...)} conditions (R-COL-04). */
    MQ1101("Two table fields share a join key but carry different on(...) conditions"),

    /** {@code on(...)} used without {@code as(...)} (R-COL-04). */
    MQ1102("on(...) requires an alias; add as(...)"),

    /** Two {@code Agg.of} fields share a name with different expressions (R-AGG-02). */
    MQ1103("Two Agg.of fields share a name with different expressions"),

    /** {@code as(...)} or {@code on(...)} on a root {@code TableField} (R-COL-03, R-COL-04). */
    MQ1104("as(...) and on(...) apply to a join, not to a root"),

    /** {@code keyset()} or {@code primaryKeyFirst(...)} without a primary key (R-QRY-03). */
    MQ1201("keyset() and primaryKeyFirst(...) require a primary key"),

    /** {@code build()} called without {@code columns(...)} (R-QRY-02). */
    MQ1202("columns(...) is required"),

    /** {@code ModelQuery.builder} given a join instead of a root {@code TableField} (R-QRY-02). */
    MQ1203("ModelQuery.builder(...) takes a root TableField, not a join"),

    /** {@code PrimaryKeyFirst.whenOffsetAbove} with a negative offset (R-QRY-03). */
    MQ1204("PrimaryKeyFirst.whenOffsetAbove(...) takes an offset that is not negative"),

    /** A value-form filter received {@code null} (api/12 §1). */
    MQ1301("A value-form filter received null; pass Optional.empty() to skip the filter"),

    /** A column or nested {@code exists(...)} path is not on or below the enclosing {@code exists} path (R-FLT-11). */
    MQ1302("A column or nested exists(...) path inside exists(...) is not on or below the given path"),

    /** A {@code Filters} or {@code Having} used after its operator returned, or while a nested one runs (D-23). */
    MQ1303("A Filters or Having was used outside its own operator"),

    /** {@code exists(...)} given a root instead of a join path (R-FLT-11). */
    MQ1304("exists(...) takes a join path, not a root"),

    /** A {@code Filters.add} predicate returned {@code null} (D-24). */
    MQ1305("A Filters.add(...) predicate returned null; skip it explicitly with when(...)"),

    /** A selected non-aggregate column is not in the group-by (R-AGG-08). */
    MQ1401("A selected non-aggregate column is not in the group-by"),

    /** Keyset paging or primary-key-first on a grouped query (R-AGG-10). */
    MQ1402("keyset() and primaryKeyFirst(...) are refused on a grouped query"),

    /** {@code Agg.sum} or {@code Agg.sumAsLong} over a column whose SQL sum type is not its result type (R-AGG-03). */
    MQ1403("Agg.sum or Agg.sumAsLong over a column whose SQL sum type differs from the declared result type"),

    /** An aggregate passed to {@code groupBy} (R-AGG-05). */
    MQ1404("groupBy(...) takes columns, not aggregates"),

    /** An {@code Agg.of} expression returned {@code null} or has a Java type other than the declared one (R-AGG-02). */
    MQ1405("An Agg.of expression returned null or an expression of another Java type"),

    /** An {@code orderBy} key that does not fit the grouping (R-AGG-08). */
    MQ1406("An orderBy key does not fit the grouping"),

    /** {@code having(...)} on an ungrouped query (R-AGG-07). */
    MQ1407("having(...) needs a grouped query: a groupBy or a selected aggregate"),

    /** A page size that is not positive, or a limit that is negative (R-EXE-06). */
    MQ2001("A page size must be positive and a limit must not be negative"),

    /** A negative offset (R-EXE-06). */
    MQ2002("An offset must not be negative"),

    /** A row's primary key mapped to {@code null} during export or primary-key-first paging (R-PAG-03). */
    MQ2201("A row's primary key mapped to null during export or primary-key-first paging"),

    /** A keyset column is NULL and has no explicit null precedence (R-PAG-05). */
    MQ2202("A keyset column is NULL and the column has no explicit null precedence"),

    /** An operation needing a primary key on a query without one (R-QRY-03). */
    MQ2203("An operation needing a primary key ran on a query without one"),

    /** Key-based paging over a selection read through a to-many join (R-PAG-13). */
    MQ2204("Offset export, keyset paging or the primary-key-first phase selected a column through a to-many join");

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
