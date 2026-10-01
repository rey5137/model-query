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

    /** {@code as(...)}, {@code on(...)} or {@code presentBy(...)} on a root {@code TableField} (R-COL-03, R-COL-04). */
    MQ1104("as(...), on(...) and presentBy(...) apply to a join, not to a root"),

    /** {@code keyset()} or {@code primaryKeyFirst(...)} without a primary key (R-QRY-03). */
    MQ1201("keyset() and primaryKeyFirst(...) require a primary key"),

    /** {@code build()} called without {@code columns(...)} (R-QRY-02). */
    MQ1202("columns(...) is required"),

    /** {@code ModelQuery.builder} given a join instead of a root {@code TableField} (R-QRY-02). */
    MQ1203("ModelQuery, ModelUpdate and ModelDelete builders take a root TableField, not a join"),

    /** {@code PrimaryKeyFirst.whenOffsetAbove} with a negative offset (R-QRY-03). */
    MQ1204("PrimaryKeyFirst.whenOffsetAbove(...) takes an offset that is not negative"),

    /** A {@code QueryCustomizer} changed the ordering or the grouping of a phase (R-QRY-11, D-33). */
    MQ1205("A QueryCustomizer changed the ordering or the grouping of a phase"),

    /** A primary-key column of array type, which cannot identify a row (R-QRY-12). */
    MQ1206("A primary-key column of array type cannot identify a row"),

    /** {@code keyset()} with a {@code Float} or {@code Double} order or primary-key column (R-QRY-13). */
    MQ1207("keyset() cannot page by a Float or Double column"),

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

    /** One {@code in} or {@code notIn} filter has more values than one statement binds (R-FLT-09). */
    MQ1306("An in(...) or notIn(...) filter has more values than one statement can bind"),

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

    /** An aggregate function over a column that has a {@code ColumnConverter} (R-AGG-04). */
    MQ1408("An aggregate function does not take a column that has a ColumnConverter"),

    /** A grouped query selects a column under a {@code presentBy} join whose key is not grouped by (R-AGG-09). */
    MQ1409("A grouped query selects a column under a presentBy join whose key columns are not all group keys"),

    /** A bulk write chose its rows with {@code where(...)} and no predicate is left (R-WRT-12). */
    MQ1601("A bulk write's where(...) left no predicate; all() is the only way to write every row"),

    /** A column is assigned twice in one update (R-WRT-13). */
    MQ1602("A column is assigned twice in one update"),

    /** {@code set(column, null)}; NULL is written with {@code setNull} (R-WRT-06). */
    MQ1603("set(column, null) is refused; write NULL with setNull(...) or a change set"),

    /** An assigned column is not on the update's root, as through a self-referencing join (R-WRT-06). */
    MQ1604("An assigned column is not on the update's root"),

    /** A primary-key or {@code @Version} column is assigned (R-WRT-13, D-61). */
    MQ1605("A primary-key or @Version column is not assignable"),

    /**
     * {@code expectVersion} with {@code keepVersion} and nothing to write, or, on first execution, on a root with no
     * {@code @Version} or with a value of the wrong type (R-WRT-07, R-WRT-16, D-61).
     */
    MQ1606("expectVersion(...) cannot apply to this update"),

    /** A bulk write's {@code @PrimaryKey} is not the root entity's id, checked on first execution (R-WRT-08, D-61). */
    MQ1608("A bulk write's primary key does not name exactly the root entity's id attributes"),

    /** {@code setExpression} on a column with a {@code ColumnConverter} (R-WRT-14). */
    MQ1609("setExpression(...) does not take a column that has a ColumnConverter"),

    /** A page size that is not positive, or a limit that is negative (R-EXE-06). */
    MQ2001("A page or chunk size must be positive and a limit must not be negative"),

    /** A negative offset (R-EXE-06). */
    MQ2002("An offset must not be negative"),

    /** Streaming on a vendor whose driver buffers the whole result outside a transaction (R-EXE-08). */
    MQ2101("Streaming requires a transaction on this vendor"),

    /** A row's primary key mapped to {@code null} during export or primary-key-first paging (R-PAG-03). */
    MQ2201("A row's primary key mapped to null during export or primary-key-first paging"),

    /** A keyset column is NULL and has no explicit null precedence (R-PAG-05). */
    MQ2202("A keyset column is NULL and the column has no explicit null precedence"),

    /** An operation needing a primary key on a query without one (R-QRY-03). */
    MQ2203("An operation needing a primary key ran on a query without one"),

    /** Key-based paging over a selection read through a to-many join (R-PAG-13). */
    MQ2204("Offset export of an ungrouped query, keyset paging or the primary-key-first phase selected a column "
            + "through a to-many join"),

    /** A keyset export page repeated a key of the page before (R-PAG-14, D-31). */
    MQ2205("A keyset export page repeated a row of the page before"),

    /** The customizer narrows the phases differently on a query with {@code primaryKeyFirst(...)} (R-PAG-15). */
    MQ2206("The phases of a query with primaryKeyFirst(...) disagree on its rows"),

    /** A sort property names no selected column or aggregate, or more than one (R-QRY-14, D-52). */
    MQ2301("A sort property resolves to no selected column or to more than one"),

    /** {@code modelquery.vendor} names an unknown vendor (R-VND-04). */
    MQ4001("modelquery.vendor names an unknown vendor"),

    /** Two {@code VendorProfile}s registered for the same vendor with no precedence rule (R-VND-03). */
    MQ4002("Two VendorProfiles are registered for the same vendor"),

    /** A configuration value is outside its allowed range (integration/50 §3). */
    MQ4003("A configuration value is outside its allowed range"),

    /**
     * {@code modelquery.vendor} is set with more than one {@code EntityManagerFactory} and no
     * {@code ModelQueryConfigurer} (R-SPR-13, D-54).
     */
    MQ4005("modelquery.vendor is set with several EntityManagerFactory beans and no ModelQueryConfigurer"),

    /**
     * A {@code ModelQueryConfig} bean of the application's own drops a {@code VendorProfile} bean or a
     * {@code modelquery.*} property (R-SPR-13, D-54).
     */
    MQ4006("A ModelQueryConfig bean of the application drops a VendorProfile bean or a modelquery.* property"),

    /**
     * A repository declares {@code ModelQueryRepository} for an entity other than its own domain type (R-SPR-12).
     */
    MQ4007("A repository's ModelQueryRepository entity is not the repository's domain type");

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
