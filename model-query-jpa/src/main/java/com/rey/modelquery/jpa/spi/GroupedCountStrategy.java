package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.core.Incubating;
import jakarta.persistence.TypedQuery;
import java.util.OptionalLong;

/**
 * Counts the groups of a grouped query inside the database, as {@code select count(*) from (<grouped query>)}. The
 * executor finds implementations with {@code ServiceLoader} and falls back to counting client-side when none answers
 * (R-EXE-03). Implementations are stateless and thread-safe.
 *
 * @implSpec R-EXE-03
 */
@Incubating
public interface GroupedCountStrategy {

    /**
     * The number of rows {@code groupedQuery} returns, or empty when this strategy does not serve the query's
     * persistence provider. The query is not executed by the caller afterwards.
     */
    OptionalLong countGroups(TypedQuery<?> groupedQuery);
}
