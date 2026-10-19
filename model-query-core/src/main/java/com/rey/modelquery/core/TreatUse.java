package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/** A treated site's own use and enclosing contexts; unknown future uses must be refused (R-VND-26). */
@EngineFacing
@Incubating
public enum TreatUse {
    /** A selected value. */
    @Incubating SELECT,
    /** An order key. */
    @Incubating ORDER,
    /** A group key. */
    @Incubating GROUP_BY,
    /** An aggregate argument. */
    @Incubating AGGREGATE,
    /** An expression operand. */
    @Incubating EXPRESSION,
    /** A predicate operand. */
    @Incubating PREDICATE,
    /** A NULL test. */
    @Incubating NULL_TEST,
    /** A negated context. */
    @Incubating NEGATED,
    /** A disjunctive context. */
    @Incubating DISJUNCTION,
    /** A LEFT-joined node. */
    @Incubating LEFT_JOIN,
    /** An INNER-joined node. */
    @Incubating INNER_JOIN,
    /** A sub-query. */
    @Incubating SUBQUERY,
    /** An outer correlation. */
    @Incubating CORRELATION,
    /** An entity type read. */
    @Incubating TYPE
}
