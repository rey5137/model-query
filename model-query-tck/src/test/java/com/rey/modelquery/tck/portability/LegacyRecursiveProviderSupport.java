package com.rey.modelquery.tck.portability;

import com.rey.modelquery.core.RecursiveCte;
import com.rey.modelquery.core.RecursiveSpec;
import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.Optional;

/** A renderer without a duplicate check, or with an unused extra check, for AC-SRC-24. */
public final class LegacyRecursiveProviderSupport extends NoSourceProviderSupport {

    private static final ThreadLocal<Boolean> EXTRA_CHECK = ThreadLocal.withInitial(() -> false);

    @Override
    public Optional<SourceRenderer> sources() {
        SourceRenderer delegate = new HibernateProviderSupport().sources().orElseThrow();
        return Optional.of(new SourceRenderer() {
            @Override
            public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
                return delegate.cte(outer, body);
            }

            @Override
            public RecursiveCte recursive(CriteriaQuery<?> outer, RecursiveSpec spec) {
                RecursiveCte cte = delegate.recursive(outer, spec);
                return EXTRA_CHECK.get()
                        ? new RecursiveCte(cte.root(), cte.depthKey(), cte.cycleKey(), spec.cb().literal(999L))
                        : new RecursiveCte(cte.root(), cte.depthKey(), cte.cycleKey(), null);
            }
        });
    }

    /** Runs with this renderer, restoring both the service loader and check mode on every exit. */
    public static void serving(boolean extraCheck, Runnable work) {
        boolean previous = EXTRA_CHECK.get();
        EXTRA_CHECK.set(extraCheck);
        try {
            serving(LegacyRecursiveProviderSupport.class, work);
        } finally {
            EXTRA_CHECK.set(previous);
        }
    }
}
