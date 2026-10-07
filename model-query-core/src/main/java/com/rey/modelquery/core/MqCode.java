package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

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

    /** {@code build()} called without {@code select(...)} or {@code fetch(...)} (R-QRY-02, R-FCH-02). */
    MQ1202("build() without select(...) or fetch(...)"),

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

    /** {@code keyset()} with an expression order key, whose cursor is defined over attribute values (R-QRY-16). */
    MQ1208("keyset() cannot order by an expression, whose cursor is defined over attribute values"),

    /** A value-form filter received {@code null}, or {@code add(label, ...)} a null or blank label (R-INS-03). */
    MQ1301("A value-form filter received null, or add(label, ...) a null or blank label"),

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

    /** A statement binds more values than one statement takes, though no one filter does (R-FLT-09, D-80). */
    MQ1307("A statement binds more values than one statement can bind; narrow its filters"),

    /**
     * A column converter's {@code toAttribute} rejected a value, such as an {@code Instant} beyond {@code Timestamp}.
     */
    MQ1308("A value cannot be converted to its column's attribute type"),

    /** {@code exists} or {@code notExists} over a sub-select whose correlation lifts no outer column. */
    MQ1309("exists(...) or notExists(...) over a sub-select needs a correlation that lifts an outer column"),

    /** An {@code Outer.column} resolved outside the correlation it was made in, or lifted through two levels. */
    MQ1310("An Outer.column was resolved outside the correlation it was made in, or lifted through two levels"),

    /** {@code Outer.column} given a column that is not on the outer query's root. */
    MQ1311("Outer.column(...) takes a column of the outer query's root"),

    /** {@code in} or {@code notIn} over a sub-select with an embeddable-valued column on either side. */
    MQ1312("in(...) and notIn(...) over a sub-select do not take an embeddable-valued column"),

    /** A selected non-aggregate column or expression does not fit the group-by (R-AGG-08, R-AGG-14). */
    MQ1401("A selected non-aggregate column or expression does not fit the group-by"),

    /** Keyset paging or primary-key-first on a grouped query (R-AGG-10). */
    MQ1402("keyset() and primaryKeyFirst(...) are refused on a grouped query"),

    /** {@code Agg.sum} or {@code Agg.sumAsLong} over a column whose SQL sum type is not its result type (R-AGG-03). */
    MQ1403("Agg.sum or Agg.sumAsLong over a column whose SQL sum type differs from the declared result type"),

    /** An aggregate passed to {@code groupBy} (R-AGG-05). */
    MQ1404("groupBy(...) takes columns, not aggregates"),

    /** An {@code Agg.of} expression returned {@code null} or has a Java type other than the declared one (R-AGG-02). */
    MQ1405("An Agg.of expression returned null or an expression of another Java type"),

    /** An {@code orderBy} key that does not fit the grouping (R-AGG-08, R-AGG-14). */
    MQ1406("An orderBy key does not fit the grouping"),

    /** {@code having(...)} on an ungrouped query (R-AGG-07). */
    MQ1407("having(...) needs a grouped query: a groupBy or a selected aggregate"),

    /** An aggregate function over a column that has a {@code ColumnConverter} (R-AGG-04). */
    MQ1408("An aggregate function does not take a column that has a ColumnConverter"),

    /** A grouped query selects a column under a {@code presentBy} join whose key is not grouped by (R-AGG-09). */
    MQ1409("A grouped query selects a column under a presentBy join whose key columns are not all group keys"),

    /** An expression reads a column with a {@code ColumnConverter} (R-COL-17). */
    MQ1501("An expression reads a column with a ColumnConverter, which the database computes over without applying it"),

    /** An expression is given a null or a value of a type it cannot bind (R-COL-18). */
    MQ1502("An expression is given a null value, or a value of a type it cannot bind (arrays, Date, Calendar, enums)"),

    /** {@code dividedBy} over two integral operands (R-COL-17). */
    MQ1503("dividedBy over two integral operands, whose division truncates on some vendors"),

    /** A CASE condition has no filter left (R-COL-17). */
    MQ1504("A CASE condition has no filter left, so it would always match"),

    /** A CASE condition uses {@code add(...)}, {@code exists(...)} or a sub-select (R-COL-17). */
    MQ1505("A CASE condition uses add(...), exists(...) or a sub-select"),

    /** {@code function(name, ...)} with a name that is not a plain identifier or is a built-in aggregate (R-COL-17). */
    MQ1506("function(name, ...) with a name that is not a plain SQL identifier or is a built-in aggregate"),

    /** An expression's declared type is not the type the provider resolves (R-COL-17). */
    MQ1507("An expression's declared type is not the type the provider resolves"),

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
     * {@code @Version} or with a value of the wrong type, or a {@code @Version} of a type a bulk update cannot
     * increment unless {@code keepVersion} (R-WRT-07, R-WRT-16, D-61).
     */
    MQ1606("expectVersion(...) or the @Version attribute cannot apply to this update"),

    /**
     * A generated change set's {@code from(model, columns)} names a column the change set does not write: a key, a
     * joined or filter-only column, or an aggregate (R-WRT-04).
     */
    MQ1607("Changes.from(...) names a column that is not writable"),

    /** A bulk write's {@code @PrimaryKey} is not the root entity's id, checked on first execution (R-WRT-08, D-61). */
    MQ1608("A bulk write's primary key does not name exactly the root entity's id attributes"),

    /** {@code setExpression} on a column with a {@code ColumnConverter} (R-WRT-14). */
    MQ1609("setExpression(...) does not take a column that has a ColumnConverter"),

    /**
     * An update {@code throughEntities()} combined with {@code keepVersion()}, {@code expectVersion(v)} or a
     * {@code setExpression}, at {@code build()} (R-WRT-41, R-WRT-46).
     */
    MQ1610("An entity-mode update does not take keepVersion(), expectVersion(...) or setExpression(...)"),

    /**
     * A write assignment's path is not a basic singular attribute of the root, or two assignments of overlapping
     * kinds name one root and path, on the first write per root per factory (R-WRT-49).
     */
    MQ1611("A write assignment does not name one assignable basic attribute of the root"),

    /**
     * A write assignment's type does not fit its attribute, or its supplier returned {@code null} or a value of the
     * wrong type (R-WRT-49).
     */
    MQ1612("A write assignment's type or value does not fit its attribute"),

    /** A join plan whose join ends up with no selected column (R-FCH-07). */
    MQ1701("A join plan's join has no selected column"),

    /**
     * A column a fetch plan needs, a child's key or an enricher's column, is read through a to-many join; checked on
     * first execution (R-FCH-02).
     */
    MQ1702("A column a fetch plan needs is read through a to-many join"),

    /** A fetch plan names the same child or join twice (R-FCH-01). */
    MQ1703("A fetch plan names the same child or join twice"),

    /** A fetch plan with a child on a grouped query, at any join depth (R-FCH-10). */
    MQ1704("A fetch plan with a child on a grouped query"),

    /** A join plan selects an aggregate or an expression, which cannot be re-rooted under the join (R-FCH-07). */
    MQ1705("A join plan selects an aggregate or an expression, which cannot be re-rooted under its join"),

    /** {@code Enricher.byKeys(...).reading(...)} with no {@code key(...)} (R-FCH-15, D-114). */
    MQ1706("Enricher.byKeys(...).reading(...) with no key(...)"),

    /** {@code Enricher.Keys.batchSize(n)} with {@code n} below 1 (R-FCH-15). */
    MQ1707("Enricher.Keys.batchSize(n) with n below 1"),

    /**
     * An insert column not mapped, mapped twice or from a column with another converter, set twice or set though
     * the model has it, or not on the root; {@code lockKeys()} on insert-values; or {@code insertReturningKeys} with
     * {@code commitEachChunk()} (R-WRT-27, R-WRT-29, R-WRT-32, R-WRT-33, D-117).
     */
    MQ1801("An insert column is unmapped, mapped or set twice, mapped with another converter, or not on the root; "
            + "or the insert's chunk options do not apply"),

    /** An insert row with a {@code null} assigned id, or a model naming the id against its generator (R-WRT-26). */
    MQ1802("An insert's id does not match the root's generator, or a row has a null assigned id"),

    /** A {@code null} insert row (R-WRT-30). */
    MQ1803("An insert row is null"),

    /**
     * A conflict clause the insert cannot render as asked: its columns, a {@code doUpdate} assignment or {@code where},
     * or a target the vendor does not honour (R-WRT-34, R-WRT-36, D-116).
     */
    MQ1804("A conflict clause names columns, assignments or filters it cannot apply"),

    /**
     * A generator outside R-WRT-26's allowlist, a {@code JOINED} or {@code @SecondaryTable} root, a composite id with
     * generated parts, or {@code @MapsId}, on an insert's first execution (R-WRT-26, D-116).
     */
    MQ1805("The insert's root has a generator or a mapping the insert cannot write"),

    /** A {@code chunked} insert-select whose source and target overlap, or whose tables are unknown (R-WRT-28). */
    MQ1806("A chunked insert-select's source and target overlap"),

    /**
     * Keys requested for an {@code IDENTITY} or assigned id, or a key type that is not the root's id type (R-WRT-33,
     * D-117).
     */
    MQ1807("The insert's keys cannot be returned as asked, or its key type is not the root's id type"),

    /** Two rows of one insert-values call share a conflict-key tuple (R-WRT-37). */
    MQ1808("Two rows of one insert share a conflict key"),

    /**
     * A {@code persist(persist, returning)} query with a clause or a selected column the flushed entity cannot fill,
     * on first execution per factory (R-WRT-48).
     */
    MQ1809("A persist returning query must select only what the flushed entity holds, with no other clause"),

    /** A page size or {@code maxPerParent} that is not positive, or a limit that is negative (R-EXE-06, R-FCH-11). */
    MQ2001("A page or chunk size and maxPerParent must be positive, and a limit must not be negative"),

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
    MQ2204("Offset export of an ungrouped query, keyset paging or the primary-key-first phase selected a column, "
            + "an expression included, through a to-many join"),

    /**
     * A keyset page holds the cursor's own primary key, or a keyset export page holds a key of the page before
     * (R-PAG-14, R-PAG-24, D-31).
     */
    MQ2205("A keyset page holds the cursor's own primary key, or an export page repeated a row of the page before"),

    /** The customizer narrows the phases differently on a query with {@code primaryKeyFirst(...)} (R-PAG-15). */
    MQ2206("The phases of a query with primaryKeyFirst(...) disagree on its rows"),

    /** A keyset page asked of a query without {@code keyset()} (R-PAG-16, R-QRY-03). */
    MQ2207("A keyset page needs a keyset() query; add keyset() or page by PageSpec"),

    /** A malformed or edited keyset cursor (R-PAG-18). */
    MQ2208("The keyset cursor is not one this library issued; start again with KeysetSpec.first"),

    /** A keyset cursor from another order, entity or deployment (R-PAG-19). */
    MQ2209("The keyset cursor belongs to another order; start again with KeysetSpec.first"),

    /** A keyset column cannot be carried in a cursor, or makes it longer than 8192 characters (R-PAG-17, R-PAG-18). */
    MQ2210("A keyset column cannot be carried in a cursor, or makes it longer than 8192 characters"),

    /** A sort property names no selected column or aggregate, or more than one (R-QRY-14, D-52). */
    MQ2301("A sort property resolves to no selected column or to more than one"),

    /** A bulk write or {@code persist} ran without an active transaction (R-WRT-18, R-WRT-39). */
    MQ2501("A bulk write or persist needs an active transaction"),

    /** A per-chunk write failed; earlier chunks stay committed (R-WRT-20). */
    MQ2502("A per-chunk write failed"),

    /**
     * An entity-mode update loaded a proxy the persistence context held for a row, and no provider support can unwrap
     * it, before any entity of the chunk is changed (R-WRT-42, R-WRT-45, D-119).
     */
    MQ2503("An entity-mode update loaded a proxy that no ProviderSupport can unwrap"),

    /** A to-one child finds two distinct rows for one key (R-FCH-04). */
    MQ2601("A to-one child found two distinct rows for one key"),

    /** An {@code Enricher.of} returns a page of another size, or {@code null} (R-FCH-08). */
    MQ2602("An Enricher.of returned a page of another size, or null"),

    /** A parent has more children than {@code maxPerParent}, or a child load's round reads its row cap (R-FCH-11). */
    MQ2603("A parent has more children than maxPerParent, or a child load's round reached its row cap"),

    /** A child row's key equals none of its round's keys, as a case-insensitive collation allows (R-FCH-05). */
    MQ2604("A child row's key equals none of the keys of its round"),

    /** {@code stream} with a fetch plan that has a child, a join plan or an enricher (R-FCH-09). */
    MQ2605("stream cannot run a fetch plan with a child, join plan or enricher"),

    /** An {@code Enricher.byKey} or {@code byKeys} lookup returned {@code null} (R-FCH-08, R-FCH-15). */
    MQ2606("An Enricher.byKey or byKeys lookup returned null"),

    /** {@code modelquery.vendor} names an unknown vendor (R-VND-04). */
    MQ4001("modelquery.vendor names an unknown vendor"),

    /** Two {@code VendorProfile}s registered for the same vendor with no precedence rule (R-VND-03). */
    MQ4002("Two VendorProfiles are registered for the same vendor"),

    /** A configuration value is outside its allowed range (integration/50 §3). */
    MQ4003("A configuration value is outside its allowed range"),

    /** {@code commitEachChunk()} with no {@code ChunkTransactions}, or none that serves the factory (R-WRT-19). */
    MQ4004("commitEachChunk() needs a ChunkTransactions that serves the EntityManagerFactory"),

    /**
     * {@code modelquery.vendor} is set with more than one {@code EntityManagerFactory} and no
     * {@code ModelQueryConfigurer} (R-SPR-13, D-54).
     */
    MQ4005("modelquery.vendor is set with several EntityManagerFactory beans and no ModelQueryConfigurer"),

    /**
     * A {@code ModelQueryConfig} bean of the application's own drops a {@code VendorProfile},
     * {@code WriteAssignment} or {@code ChunkTransactions} bean, or a set {@code modelquery.*} property (R-SPR-13, D-54).
     */
    MQ4006("A ModelQueryConfig bean of the application drops a VendorProfile, WriteAssignment or ChunkTransactions bean,"
            + " or a set modelquery.* property"),

    /**
     * A repository declares {@code ModelQueryRepository} for an entity other than its own domain type (R-SPR-12).
     */
    MQ4007("A repository's ModelQueryRepository entity is not the repository's domain type"),

    /**
     * The starter cannot add the repository fragment because the factory bean definition already sets
     * {@code customImplementation} (R-SPR-02, D-113).
     */
    MQ4008("The starter cannot add the repository fragment because the factory bean definition already sets "
            + "customImplementation"),

    /** A bulk insert on a factory whose persistence provider has no {@code InsertSupport} (R-VND-14). */
    MQ4009("A bulk insert needs an InsertSupport for the factory's persistence provider");

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
