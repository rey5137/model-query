package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.spi.ConflictClause;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Optional;

/**
 * An insert-values definition's conflict clause as {@link com.rey.modelquery.jpa.spi.InsertSupport} takes it: the
 * definition renders its own update (R-WRT-34, D-117).
 *
 * @param insert an insert-values with a conflict clause
 * @param options how the update's {@code where} renders, as the executor's other statements do
 */
record InsertConflict<E>(ModelInsert<E, ?> insert, RenderOptions options) implements ConflictClause<E> {

    @Override
    public List<String> keyAttributes() {
        return insert.conflictKeys();
    }

    @Override
    public boolean doNothing() {
        return insert.conflictSkips();
    }

    @Override
    public Optional<Predicate> update(Root<E> target, Root<E> excluded, CriteriaBuilder cb, Assignments assign) {
        return insert.buildConflictUpdate(target, excluded, cb, options, assign::value, assign::expression);
    }
}
