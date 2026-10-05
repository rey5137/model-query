package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;
import java.util.Optional;

/**
 * A read-only view of the query definition, handed to a {@link QueryCustomizer} so it can adapt to what the query
 * selects. It carries no Criteria state; every list it returns throws on mutation.
 *
 * @implSpec R-QRY-07
 */
@Incubating
public interface QuerySpec {

    /** The entity the query is rooted at. */
    Class<?> rootEntity();

    /** The columns selected for the model, in order, without any primary key added for paging. */
    List<SelectField<?, ?>> columns();

    /** The primary key, when the query defines one. */
    Optional<PrimaryKey<?, ?>> primaryKey();

    /** The ordering keys, in order. */
    List<OrderField<?, ?>> orderBy();

    /** Whether keyset paging is allowed. */
    boolean keyset();

    /** The group-by keys, in order; empty when the query has no group-by. */
    @Incubating
    List<ScalarField<?, ?>> groupBy();

    /** Whether the query is grouped: it has a group-by or selects an aggregate (R-AGG-07). */
    @Incubating
    boolean isGrouped();
}
