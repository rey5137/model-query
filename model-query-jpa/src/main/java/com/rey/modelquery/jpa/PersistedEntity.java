package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.EntityWriteSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The new entity {@code persist} writes: the root instantiated with its no-arg constructor, each model attribute set
 * through the attribute's metamodel member, the field, or for property access the setter paired with the getter
 * {@link Attribute#getJavaMember} returns. An embeddable on the way is the one the constructor left, else a new one
 * from its no-arg constructor; a to-one is {@link EntityManager#getReference} of its target's id. Constructors, fields
 * and setters are reached with {@code setAccessible} (R-WRT-39). An update {@code throughEntities()} sets its
 * assignments on each managed entity it loaded the same way, but through the provider's {@link EntityWriteSupport}
 * when it has one (R-WRT-42, D-119), and {@code persist} returning a model reads its columns back from the flushed
 * entity through the same members (R-WRT-48).
 */
final class PersistedEntity {

    private PersistedEntity() {
    }

    /**
     * A new {@code root} with each of {@code attributes} set to the value at its index in {@code values}, which
     * {@code ModelPersist} has converted; the metamodel check has refused an embeddable it cannot instantiate.
     */
    static <E> E create(EntityManager em, EntityType<E> root, List<String> attributes, List<Object> values) {
        E entity = instantiate(root.getJavaType());
        for (int i = 0; i < attributes.size(); i++) {
            set(em, root, entity, attributes.get(i), values.get(i), null);
        }
        return entity;
    }

    /**
     * Sets each of {@code attributes} on {@code entity}, a managed {@code root} entity and never a proxy, to the value
     * at its index in {@code values}, which {@code ModelUpdate} has converted: through {@code writes}, the provider's
     * attribute access, when present, else through each attribute's metamodel member, whose plain field write a
     * bytecode-enhanced or woven entity's change tracking does not see, so its flush may write nothing (R-WRT-42,
     * D-119).
     */
    static void assign(EntityManager em, ManagedType<?> root, Object entity, List<String> attributes,
            List<Object> values, Optional<EntityWriteSupport> writes) {
        for (int i = 0; i < attributes.size(); i++) {
            set(em, root, entity, attributes.get(i), values.get(i), writes.orElse(null));
        }
    }

    /**
     * The value of {@code path} on {@code entity}, a managed {@code root} entity, read through each attribute's
     * metamodel member: {@code null} under an embeddable the entity holds as {@code null}, and for a to-one, which
     * {@code ModelQuery.checkReturning} lets the path end at only by naming its target's id, the target's id from
     * {@link PersistenceUnitUtil#getIdentifier}, which reads a reference without initializing it (R-WRT-48).
     */
    static Object get(PersistenceUnitUtil util, ManagedType<?> root, Object entity, String path) {
        ManagedType<?> type = root;
        Object value = entity;
        for (String segment : path.split("\\.")) {
            if (value == null) {
                return null;
            }
            Attribute<?, ?> attribute = type.getAttribute(segment);
            value = read(attribute, value);
            if (attribute instanceof SingularAttribute<?, ?> toOne && toOne.isAssociation()) {
                return value == null ? null : util.getIdentifier(value);
            }
            if (attribute instanceof SingularAttribute<?, ?> singular
                    && singular.getType() instanceof ManagedType<?> embeddable) {
                type = embeddable;
            }
        }
        return value;
    }

    /**
     * Sets {@code path} of {@code entity}, a {@code root} entity, to {@code value}: through {@code writes} when not
     * {@code null}, a managed entity's attribute named by its path from the entity, else on each owner through the
     * attribute's metamodel member.
     */
    private static void set(EntityManager em, ManagedType<?> root, Object entity, String path, Object value,
            EntityWriteSupport writes) {
        String[] segments = path.split("\\.");
        Target target = new Target(em, entity, writes);
        ManagedType<?> type = root;
        Object owner = entity;
        for (int i = 0; i < segments.length - 1; i++) {
            Attribute<?, ?> embedded = type.getAttribute(segments[i]);
            String prefix = String.join(".", Arrays.copyOfRange(segments, 0, i + 1));
            if (embedded instanceof SingularAttribute<?, ?> toOne && toOne.isAssociation()) {
                // A to-one named by its target's id (customer.id) binds a reference, as one named alone does
                setReference(target, prefix, toOne, owner, String.join(".", Arrays.copyOfRange(segments, i + 1,
                        segments.length)), value);
                return;
            }
            Object next = read(embedded, owner);
            if (next == null) {
                next = instantiate(embedded.getJavaType());
                target.write(prefix, embedded, owner, next);
            }
            type = (ManagedType<?>) ((SingularAttribute<?, ?>) embedded).getType();
            owner = next;
        }
        Attribute<?, ?> attribute = type.getAttribute(segments[segments.length - 1]);
        if (attribute instanceof SingularAttribute<?, ?> toOne && toOne.isAssociation()) {
            setReference(target, path, toOne, owner, null, value);
            return;
        }
        target.write(path, attribute, owner, value);
    }

    /** The entity a write sets attributes of, and the provider's attribute access, or {@code null} for none. */
    private record Target(EntityManager em, Object entity, EntityWriteSupport writes) {

        /** Sets {@code attribute}, at {@code path} from the entity, on {@code owner}, the entity or an embeddable. */
        void write(String path, Attribute<?, ?> attribute, Object owner, Object value) {
            if (writes == null) {
                PersistedEntity.write(attribute, owner, value);
                return;
            }
            refuseNullPrimitive(attribute, value);
            writes.set(em, entity, path, value);
        }
    }

    /**
     * Sets {@code toOne}, at {@code path} from the entity, on {@code owner} to {@link EntityManager#getReference} of
     * {@code id}, its target's id, which the column names as {@code idPath} after the to-one, or {@code null} when it
     * names the to-one alone.
     */
    private static void setReference(Target target, String path, SingularAttribute<?, ?> toOne, Object owner,
            String idPath, Object id) {
        if (idPath != null && !(toOne.getType() instanceof IdentifiableType<?> type && type.hasSingleIdAttribute()
                && idPath.equals(type.getId(type.getIdType().getJavaType()).getName()))) {
            throw new IllegalStateException(describe(toOne) + ": an entity write sets a to-one by its target's id, "
                    + "and the column names " + idPath + " on it");
        }
        target.write(path, toOne, owner, id == null ? null : target.em().getReference(toOne.getType().getJavaType(),
                id));
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            throw rethrown(e, type.getSimpleName() + "'s no-arg constructor");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName() + ": an entity write cannot call its no-arg "
                    + "constructor", e);
        }
    }

    private static Object read(Attribute<?, ?> attribute, Object owner) {
        Member member = attribute.getJavaMember();
        try {
            if (member instanceof Field field) {
                field.setAccessible(true);
                return field.get(owner);
            }
            Method getter = getter(attribute, member);
            getter.setAccessible(true);
            return getter.invoke(owner);
        } catch (InvocationTargetException e) {
            throw rethrown(e, describe(attribute) + "'s getter");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(describe(attribute) + ": an entity write cannot read it", e);
        }
    }

    /**
     * Sets {@code attribute} on {@code owner} through its metamodel member: the field, which a bytecode-enhanced or
     * woven entity's change tracking does not see, or the setter paired with the getter.
     */
    private static void write(Attribute<?, ?> attribute, Object owner, Object value) {
        refuseNullPrimitive(attribute, value);
        Member member = attribute.getJavaMember();
        try {
            if (member instanceof Field field) {
                field.setAccessible(true);
                field.set(owner, value);
                return;
            }
            Method setter = setter(attribute, getter(attribute, member), owner.getClass());
            setter.setAccessible(true);
            setter.invoke(owner, value);
        } catch (InvocationTargetException e) {
            throw rethrown(e, describe(attribute) + "'s setter");
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new IllegalStateException(describe(attribute) + ": an entity write cannot set it to a "
                    + (value == null ? "null" : value.getClass().getSimpleName()), e);
        }
    }

    private static void refuseNullPrimitive(Attribute<?, ?> attribute, Object value) {
        if (value == null && attribute.getJavaType().isPrimitive()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1308, describe(attribute) + " is a primitive "
                    + attribute.getJavaType().getSimpleName() + ", which a null cannot be set to; use the wrapper "
                    + "type, or give the row a value");
        }
    }

    private static Method getter(Attribute<?, ?> attribute, Member member) {
        if (member instanceof Method getter) {
            return getter;
        }
        throw new IllegalStateException(describe(attribute) + ": the metamodel reports " + member
                + " as its member, neither a field nor a getter");
    }

    /**
     * The setter paired with {@code getter}: {@code setX} for {@code getX} or {@code isX}, taking its type, declared
     * on {@code owner} or a superclass, which need not be the one declaring the getter.
     */
    private static Method setter(Attribute<?, ?> attribute, Method getter, Class<?> owner) {
        String name = getter.getName();
        String property = name.startsWith("is") ? name.substring(2) : name.substring(3);
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                return type.getDeclaredMethod("set" + property, getter.getReturnType());
            } catch (NoSuchMethodException e) {
                // declared higher up, if anywhere
            }
        }
        throw new IllegalStateException(describe(attribute) + ": property access needs set" + property + "("
                + getter.getReturnType().getSimpleName() + ") beside " + name + "()");
    }

    /** The exception a constructor, getter or setter threw, unchanged when unchecked. */
    private static RuntimeException rethrown(InvocationTargetException e, String what) {
        if (e.getCause() instanceof RuntimeException unchecked) {
            return unchecked;
        }
        if (e.getCause() instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(what + " threw", e.getCause());
    }

    private static String describe(Attribute<?, ?> attribute) {
        return attribute.getDeclaringType().getJavaType().getSimpleName() + "." + attribute.getName();
    }
}
