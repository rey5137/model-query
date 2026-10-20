package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import java.util.List;
import java.util.Optional;

/** The provider's treated-path rendering and statement checks (R-VND-26, INV-7). */
@EngineFacing
@Incubating
public interface TreatRenderer {

    /** Reads {@code from} as its entity subtype (R-COL-34). */
    @Incubating
    <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype);

    /** The reason a site cannot preserve treated-path semantics, or empty when admitted (R-VND-26). */
    @Incubating
    Optional<String> refusal(TreatSite site);

    /**
     * The translation's refusal reason, or empty when the statement preserves all intended rows (R-VND-26).
     * {@code expected} lists the narrowings the engine rendered in this statement on purpose, in build order and
     * compared as a multiset; empty when it rendered none (D-157).
     */
    @Incubating
    Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape,
            List<ExpectedNarrowing> expected);
}
