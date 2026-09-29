package com.rey.modelquery.tck.arch.fixture.core;

/** Violates: core imports only jakarta.persistence and the JDK (org.hibernate edge). */
public class BadCoreHibernate {
    org.hibernate.Session session;
}
