package com.rey.modelquery.tck.arch.fixture.jpa;

/** Violates: jpa does not import org.hibernate. */
public class BadJpaHibernate {
    org.hibernate.SessionFactory factory;
}
