package com.rey.modelquery.jpa;

import com.rey.modelquery.core.Condition;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.spi.ConflictClause;
import jakarta.persistence.Parameter;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An insert-values definition's conflict clause as {@link com.rey.modelquery.jpa.spi.InsertSupport} takes it: the
 * definition renders its own update (R-WRT-34, D-117). It keeps the statement parameters its last rendered
 * {@code where} holds, which the executor binds on the statement {@code insertValues} returns (R-FLT-08): the
 * executor makes one clause per statement.
 */
final class InsertConflict<E> implements ConflictClause<E> {

    private final ModelInsert<E, ?> insert;
    private final RenderOptions options;
    private final Map<String, Object> written;
    private final Map<ParameterExpression<?>, Object> parameters = new LinkedHashMap<>();

    /**
     * @param insert an insert-values with a conflict clause
     * @param options how the update's {@code where} renders, as the executor's other statements do
     * @param written the {@code UPDATE} write assignments' values by attribute path, which a {@code doUpdate} sets
     *     after its own unless it sets the attribute itself (R-WRT-49)
     */
    InsertConflict(ModelInsert<E, ?> insert, RenderOptions options, Map<String, Object> written) {
        this.insert = insert;
        this.options = options;
        this.written = written;
    }

    @Override
    public List<String> keyAttributes() {
        return insert.conflictKeys();
    }

    @Override
    public Optional<String> constraintName() {
        return insert.conflictConstraint();
    }

    @Override
    public List<Condition> arbiter() {
        return insert.conflictArbiter();
    }

    @Override
    public boolean doNothing() {
        return insert.conflictSkips();
    }

    @Override
    public Optional<Predicate> update(Root<E> target, Root<E> excluded, CriteriaBuilder cb, Assignments assign) {
        parameters.clear();
        return insert.buildConflictUpdate(target, excluded, cb, options, written, assign::value,
                assign::expression, parameters::put);
    }

    /**
     * Binds on {@code statement} the parameters of the {@code where} last rendered for it, if any (R-FLT-08), then
     * forgets them, so a statement whose {@code where} was never rendered is never bound another statement's values.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bind(Query statement) {
        parameters.forEach((parameter, value) -> statement.setParameter((Parameter) parameter, value));
        parameters.clear();
    }
}
