package com.rey.modelquery.hibernate;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.sql.SqmTranslation;
import org.hibernate.query.sqm.tree.SqmDmlStatement;
import org.hibernate.sql.ast.tree.MutationStatement;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;

/**
 * One Hibernate line's mutation calls ({@link Hibernate6Mutations}, {@link Hibernate7Mutations}): the translation of a
 * bulk update or delete, its parameter bindings, the query-space invalidation Hibernate's own bulk path does and the
 * {@code ExecutionContext} its mutation executor builds. Every call here reaches an {@code org.hibernate..internal..}
 * type on one line and a differently-shaped one on the other, so each adapter {@linkplain #probes probes} for its own
 * line's classes and methods and keeps every internal type to itself; {@link HibernateMutations} takes the first
 * adapter whose probe passes, and answers {@code MQ4011} when none does, so a line whose internals moved refuses a
 * returning write instead of running SQL it may have translated wrongly (R-WRT-50, RFC 0010 §Compatibility).
 *
 * @implSpec R-WRT-50, R-VND-17
 */
interface Mutations {

    /** Whether every class and method this adapter calls exists on {@code sf} (R-WRT-50). */
    boolean probes(SessionFactoryImplementor sf);

    /** The Hibernate version this adapter was written and tested against, for the once-per-version WARN. */
    String tested();

    /** {@code dml}'s translation on this line, with its parameter bindings already created (R-WRT-50). */
    Translation translate(SessionFactoryImplementor sf, SqmDmlStatement<?> dml, QueryOptions options,
            DomainQueryExecutionContext context);

    /** Schedules the query-space invalidation this line's own bulk path performs for {@code dml} (R-WRT-50). */
    void invalidate(SharedSessionContractImplementor session, SqmDmlStatement<?> dml);

    /** This line's {@code ExecutionContext} for {@code context}, as its own mutation executor builds it (R-WRT-50). */
    ExecutionContext execution(DomainQueryExecutionContext context);

    /**
     * A statement {@code dml} translated on one line: the translation whose {@code SqlAst} the returning columns are
     * attached to, and the bindings its binders are bound with. Both are provider types, never an internal one.
     */
    record Translation(SqmTranslation<? extends MutationStatement> translation, JdbcParameterBindings bindings) {}
}
