package com.rey.modelquery.hibernate;

import com.rey.modelquery.jpa.spi.EntityWriteSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
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

    /** The resolved attribute chains of each factory, by entity class and dotted path; weak on the factory. */
    private static final Map<EntityManagerFactory,
            SoftReference<Map<Class<?>, Map<String, AttributeMapping[]>>>> CHAINS = Collections
            .synchronizedMap(new WeakHashMap<>());

    @Override
    public Object unproxy(Object entity) {
        return Hibernate.unproxy(entity);
    }

    @Override
    public void set(EntityManager em, Object entity, String attribute, Object value) {
        AttributeMapping[] chain = chain(em, entity.getClass(), attribute);
        int dot = attribute.indexOf('.');
        String top = dot < 0 ? attribute : attribute.substring(0, dot);
        Object owner = entity;
        for (int i = 0; i < chain.length - 1; i++) {
            owner = chain[i].getPropertyAccess().getGetter().get(owner);
        }
        AttributeMapping mapping = chain[chain.length - 1];
        // An unloaded lazy attribute reads as null whatever the row holds, so it is written whatever the value
        boolean changed = !Hibernate.isPropertyInitialized(entity, top)
                || !Objects.deepEquals(mapping.getPropertyAccess().getGetter().get(owner), value);
        mapping.getPropertyAccess().getSetter().set(owner, value);
        if (changed && entity instanceof SelfDirtinessTracker tracker) {
            tracker.$$_hibernate_trackChange(top);
        }
    }

    /**
     * The attribute mappings along {@code attribute}, dotted through embeddables, of {@code type}, resolved once per
     * factory. The cache holds them softly, since a mapping reaches its factory and a strong value would keep a
     * weakly keyed factory alive.
     */
    private static AttributeMapping[] chain(EntityManager em, Class<?> type, String attribute) {
        EntityManagerFactory emf = em.getEntityManagerFactory();
        Map<Class<?>, Map<String, AttributeMapping[]>> byType = null;
        SoftReference<Map<Class<?>, Map<String, AttributeMapping[]>>> held = CHAINS.get(emf);
        if (held != null) {
            byType = held.get();
        }
        if (byType == null) {
            byType = new ConcurrentHashMap<>();
            CHAINS.put(emf, new SoftReference<>(byType));
        }
        return byType.computeIfAbsent(type, t -> new ConcurrentHashMap<>()).computeIfAbsent(attribute, path -> {
            String[] segments = path.split("\\.");
            ManagedMappingType managed = emf.unwrap(SessionFactoryImplementor.class).getMappingMetamodel()
                    .getEntityDescriptor(type);
            var chain = new AttributeMapping[segments.length];
            for (int i = 0; i < segments.length; i++) {
                chain[i] = mapping(managed, segments[i], path);
                if (i < segments.length - 1) {
                    managed = ((EmbeddableValuedModelPart) chain[i]).getEmbeddableTypeDescriptor();
                }
            }
            return chain;
        });
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
