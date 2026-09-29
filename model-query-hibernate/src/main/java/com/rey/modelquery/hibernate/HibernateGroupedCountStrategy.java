package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.jpa.spi.GroupedCountStrategy;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.OptionalLong;
import org.hibernate.query.SelectionQuery;

/**
 * Counts the groups of a grouped query with {@code SelectionQuery#getResultCount()}, which renders
 * {@code select count(*) from (<grouped query>)}. Registered with {@code ServiceLoader}.
 *
 * @implSpec R-EXE-03
 */
@Incubating
public final class HibernateGroupedCountStrategy implements GroupedCountStrategy {

    @Override
    public OptionalLong countGroups(TypedQuery<?> groupedQuery) {
        SelectionQuery<?> selection;
        try {
            selection = groupedQuery.unwrap(SelectionQuery.class);
        } catch (PersistenceException notHibernate) {
            return OptionalLong.empty(); // another provider: the executor counts client-side
        }
        return OptionalLong.of(selection.getResultCount());
    }
}
