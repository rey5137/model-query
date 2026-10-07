package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.EntityManager;

/**
 * What an update {@code throughEntities()} needs of the persistence provider to change a managed entity, which
 * portable JPA has no API for: the instance behind a proxy the persistence context holds, and an attribute write the
 * provider's change tracking sees, built from {@code jakarta.persistence} types only (INV-7). Reached through
 * {@link ProviderSupport#entityWrites()}; without one, the engine writes each attribute through its metamodel member,
 * which a bytecode-enhanced or woven entity's change tracking does not see, and refuses a proxy with {@code MQ2503}.
 * Implementations are stateless and thread-safe (api/14 R-WRT-42, D-119).
 *
 * @implSpec R-VND-14, R-WRT-42, D-119
 */
@Incubating
public interface EntityWriteSupport {

    /**
     * The instance behind {@code entity}, initialised, when it is a proxy of the provider, else {@code entity} itself.
     * The engine asks only for a loaded instance whose class is no mapped entity class.
     */
    Object unproxy(Object entity);

    /**
     * Sets {@code attribute} of {@code entity}, an instance managed by {@code em} and never a proxy, to {@code value},
     * as the provider's own attribute access does, so its change tracking sees a value that differs. The attribute is
     * named as a model column names it, dotted through embeddables, the embeddable on the way present; a to-one is
     * named alone and {@code value} is its target or {@code null}.
     */
    void set(EntityManager em, Object entity, String attribute, Object value);
}
