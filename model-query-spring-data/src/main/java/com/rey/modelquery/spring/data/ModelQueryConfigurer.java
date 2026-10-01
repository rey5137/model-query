package com.rey.modelquery.spring.data;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.jpa.ModelQueryConfig;
import jakarta.persistence.EntityManagerFactory;

/**
 * Chooses the {@link ModelQueryConfig} of each {@code EntityManagerFactory}: a context with several databases may need
 * a vendor, a timeout or a profile per factory, where the shared config serves them all. At most one configurer bean
 * exists in a context (R-SPR-13, D-56).
 *
 * @implSpec R-SPR-13
 */
@Incubating
@FunctionalInterface
public interface ModelQueryConfigurer {

    /**
     * The config of the repositories on {@code factory}, called once per repository.
     *
     * @param shared  the context's {@code ModelQueryConfig} bean
     * @param factory the factory of the repository's {@code EntityManager}
     * @return the config to use, {@code shared} to keep it; never {@code null}
     */
    ModelQueryConfig configure(ModelQueryConfig shared, EntityManagerFactory factory);
}
