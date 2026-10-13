package com.rey.modelquery.tck.arch.fixture.hibernate;

import org.hibernate.query.sqm.internal.DomainParameterXref;

/** Deliberately bad fixture: imports a Hibernate internal outside the two mutation adapters (R-WRT-50). */
public final class BadHibernateInternalImport {

    private BadHibernateInternalImport() {}

    /** Holds the internal type so the import survives into bytecode, where the layering rule finds it. */
    public static Class<?> internal() {
        return DomainParameterXref.class;
    }
}
