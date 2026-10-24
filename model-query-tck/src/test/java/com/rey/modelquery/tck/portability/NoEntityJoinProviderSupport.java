package com.rey.modelquery.tck.portability;

import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import java.util.Optional;

/** A provider with working joined sources and the default, absent entity-join hook (AC-SRC-58). */
public final class NoEntityJoinProviderSupport extends NoSourceProviderSupport {

    @Override
    public Optional<SourceRenderer> sources() {
        return new HibernateProviderSupport().sources();
    }

    public static void serving(Runnable work) {
        serving(NoEntityJoinProviderSupport.class, work);
    }
}
