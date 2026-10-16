package com.rey.modelquery.tck.arch.fixture.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.List;

/** Violates: a {@code Tuple} read outside the check site (AC-SRC-25). */
public class BadTupleRead {

    /** {@code read} is no check-site method, so its raw read may bypass a source's checks. */
    List<Tuple> read(EntityManager em, CriteriaQuery<Tuple> query) {
        return em.createQuery(query).getResultList();
    }
}
