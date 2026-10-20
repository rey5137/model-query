package com.rey.modelquery.hibernate;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.spi.QueryImplementor;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.sql.ast.tree.select.SelectStatement;

/**
 * One Hibernate line's select translation for the statement preflight ({@link Hibernate6Preflight},
 * {@link Hibernate7Preflight}): the SQM of a query built from a criteria statement, translated to Hibernate's SQL AST
 * through the line's select translator, with no JDBC call. The translator's creation context is reached differently
 * on 6.6 and 7.4, and the parameter cross-reference is an {@code org.hibernate..internal..} type, so each adapter
 * {@linkplain #probes probes} for its own line and keeps that type to itself; {@link HibernateTreatRenderer} takes the
 * first adapter whose probe passes, and refuses every preflight when none does (R-VND-26, RFC 0016 §11 guard 3).
 *
 * @implSpec R-VND-26
 */
interface PreflightTranslation {

    /** Whether every class and method this adapter calls exists on {@code sf} (R-VND-26). */
    boolean probes(SessionFactoryImplementor sf);

    /** The Hibernate version this adapter was written and tested against. */
    String tested();

    /** {@code sqm}, the statement of {@code query}, translated to the SQL AST its execution would render. */
    SelectStatement translate(SessionFactoryImplementor sf, QueryImplementor<?> query, SqmSelectStatement<?> sqm);
}
