package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;

/**
 * What a {@link ProviderSupport} reports of how a to-one attribute's path to its target's id renders in a bulk
 * statement (R-COL-29, D-136).
 *
 * @implSpec R-COL-29
 */
@Incubating
public enum ForeignKeyColumn {

    /**
     * {@code toOne.id} renders as a column of the entity's own table, with no join and no formula, and that column
     * references the target's primary key.
     */
    LOCAL_COLUMN,

    /** {@code toOne.id} renders through a join, a formula or another table, so the statement would drop rows. */
    JOINS,

    /** The provider cannot tell: no mapping answers for the entity and attribute. */
    UNKNOWN
}
