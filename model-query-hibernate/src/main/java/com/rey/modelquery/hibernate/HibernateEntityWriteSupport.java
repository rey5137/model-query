package com.rey.modelquery.hibernate;

import com.rey.modelquery.jpa.spi.EntityWriteSupport;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SelfDirtinessTracker;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.EmbeddableValuedModelPart;
import org.hibernate.metamodel.mapping.ManagedMappingType;

/**
 * Hibernate's {@link EntityWriteSupport}, compiled against Hibernate 6.6 and run on 7.x as well: a proxy unwrapped with
 * {@link Hibernate#unproxy}, and an attribute set through the persister's property access, which for a
 * bytecode-enhanced entity also sets an embeddable's owner and marks a lazy attribute loaded. That property access
 * does not mark the attribute dirty, so on an entity that tracks its own dirtiness a value that differs is reported
 * with {@code $$_hibernate_trackChange} under its top-level property, as the enhanced class's own write does (api/14
 * R-WRT-42, D-119).
 *
 * @implSpec R-WRT-42, D-119
 */
final class HibernateEntityWriteSupport implements EntityWriteSupport {

    @Override
    public Object unproxy(Object entity) {
        return Hibernate.unproxy(entity);
    }

    @Override
    public void set(EntityManager em, Object entity, String attribute, Object value) {
        String[] segments = attribute.split("\\.");
        ManagedMappingType type = em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel().getEntityDescriptor(entity.getClass());
        Object owner = entity;
        for (int i = 0; i < segments.length - 1; i++) {
            AttributeMapping embedded = mapping(type, segments[i], attribute);
            owner = embedded.getPropertyAccess().getGetter().get(owner);
            type = ((EmbeddableValuedModelPart) embedded).getEmbeddableTypeDescriptor();
        }
        AttributeMapping mapping = mapping(type, segments[segments.length - 1], attribute);
        // An unloaded lazy attribute reads as null whatever the row holds, so it is written whatever the value
        boolean changed = !Hibernate.isPropertyInitialized(entity, segments[0])
                || !Objects.deepEquals(mapping.getPropertyAccess().getGetter().get(owner), value);
        mapping.getPropertyAccess().getSetter().set(owner, value);
        if (changed && entity instanceof SelfDirtinessTracker tracker) {
            tracker.$$_hibernate_trackChange(segments[0]);
        }
    }

    private static AttributeMapping mapping(ManagedMappingType type, String name, String attribute) {
        AttributeMapping mapping = type.findAttributeMapping(name);
        if (mapping == null) {
            throw new IllegalStateException(type.getJavaType().getJavaTypeClass().getSimpleName() + " maps no "
                    + "attribute " + name + " of " + attribute);
        }
        return mapping;
    }
}
