package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import jakarta.persistence.EntityManager;
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

/**
 * The new entity {@code persist} writes: the root instantiated with its no-arg constructor, each model attribute set
 * through the attribute's metamodel member, the field, or for property access the setter paired with the getter
 * {@link Attribute#getJavaMember} returns. An embeddable on the way is the one the constructor left, else a new one
 * from its no-arg constructor; a to-one is {@link EntityManager#getReference} of its target's id. Constructors, fields
 * and setters are reached with {@code setAccessible} (R-WRT-39).
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
            set(em, root, entity, attributes.get(i), values.get(i));
        }
        return entity;
    }

    private static void set(EntityManager em, ManagedType<?> root, Object entity, String path, Object value) {
        String[] segments = path.split("\\.");
        ManagedType<?> type = root;
        Object owner = entity;
        for (int i = 0; i < segments.length - 1; i++) {
            Attribute<?, ?> embedded = type.getAttribute(segments[i]);
            if (embedded instanceof SingularAttribute<?, ?> toOne && toOne.isAssociation()) {
                // A to-one named by its target's id (customer.id) binds a reference, as one named alone does
                setReference(em, toOne, owner, String.join(".", Arrays.copyOfRange(segments, i + 1,
                        segments.length)), value);
                return;
            }
            Object next = read(embedded, owner);
            if (next == null) {
                next = instantiate(embedded.getJavaType());
                write(embedded, owner, next);
            }
            type = (ManagedType<?>) ((SingularAttribute<?, ?>) embedded).getType();
            owner = next;
        }
        Attribute<?, ?> attribute = type.getAttribute(segments[segments.length - 1]);
        if (attribute instanceof SingularAttribute<?, ?> toOne && toOne.isAssociation()) {
            setReference(em, toOne, owner, null, value);
            return;
        }
        write(attribute, owner, value);
    }

    /**
     * Sets {@code toOne} on {@code owner} to {@link EntityManager#getReference} of {@code id}, its target's id, which
     * the column names as {@code idPath} after the to-one, or {@code null} when it names the to-one alone.
     */
    private static void setReference(EntityManager em, SingularAttribute<?, ?> toOne, Object owner, String idPath,
            Object id) {
        if (idPath != null && !(toOne.getType() instanceof IdentifiableType<?> target && target.hasSingleIdAttribute()
                && idPath.equals(target.getId(target.getIdType().getJavaType()).getName()))) {
            throw new IllegalStateException(describe(toOne) + ": persist sets a to-one by its target's id, and the "
                    + "column names " + idPath + " on it");
        }
        write(toOne, owner, id == null ? null : em.getReference(toOne.getType().getJavaType(), id));
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            throw rethrown(e, type.getSimpleName() + "'s no-arg constructor");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName() + ": persist cannot call its no-arg constructor", e);
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
            throw new IllegalStateException(describe(attribute) + ": persist cannot read it", e);
        }
    }

    private static void write(Attribute<?, ?> attribute, Object owner, Object value) {
        if (value == null && attribute.getJavaType().isPrimitive()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1308, describe(attribute) + " is a primitive "
                    + attribute.getJavaType().getSimpleName() + ", which a null cannot be set to; use the wrapper "
                    + "type, or give the row a value");
        }
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
            throw new IllegalStateException(describe(attribute) + ": persist cannot set it to a "
                    + (value == null ? "null" : value.getClass().getSimpleName()), e);
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
