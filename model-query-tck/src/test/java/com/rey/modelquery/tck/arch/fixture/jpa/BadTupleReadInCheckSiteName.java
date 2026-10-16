package com.rey.modelquery.tck.arch.fixture.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.List;

/** Violates: a method named like the check site, in a class that is not one (AC-SRC-25). */
public class BadTupleReadInCheckSiteName {

    /** {@code rows} is a check-site name, but this class is no check-site owner. */
    List<Tuple> rows(EntityManager em, CriteriaQuery<Tuple> query) {
        return em.createQuery(query).getResultList();
    }
}
