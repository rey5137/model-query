package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.EntityJoinRenderer;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Function;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.sqm.tree.SqmJoinType;

/** Root entity joins on both supported Hibernate lines, applying the complete predicate once (R-SRC-24). */
@Incubating
final class HibernateEntityJoinRenderer implements EntityJoinRenderer {

    @Incubating
    static final HibernateEntityJoinRenderer INSTANCE =
            new HibernateEntityJoinRenderer(EntityMethods.of(JpaFrom.class));

    private final Optional<EntityMethods> methods;

    @Incubating
    HibernateEntityJoinRenderer(Optional<EntityMethods> methods) {
        this.methods = methods;
    }

    /** Cache the per-line signatures together: 6.6 ANDs repeated on calls, while 7.4 replaces them. */
    @Incubating
    record EntityMethods(Method join, Method on) {
        @Incubating
        static Optional<EntityMethods> of(Class<?> from) {
            try {
                Method join = from.getMethod("join", Class.class, SqmJoinType.class);
                return Optional.of(new EntityMethods(join, join.getReturnType().getMethod("on", Predicate[].class)));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }
    }

    @Override
    @Incubating
    @SuppressWarnings("unchecked")
    public <E> From<?, E> join(From<?, ?> anchor, Class<E> entity, JoinKind kind,
            Function<From<?, E>, Predicate> on) {
        if (!(anchor instanceof Root<?>) || methods.isEmpty()) {
            throw new ModelQueryConfigurationException(MqCode.MQ4014,
                    "Hibernate cannot attach an entity join to the statement root (ProviderSupport#entityJoins())");
        }
        EntityMethods linked = methods.orElseThrow();
        From<?, E> joined = (From<?, E>) invoke(linked.join(), anchor, entity,
                kind == JoinKind.INNER ? SqmJoinType.INNER : SqmJoinType.LEFT);
        invoke(linked.on(), joined, (Object) new Predicate[] {on.apply(joined)});
        return joined;
    }

    @Incubating
    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Hibernate entity join failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Hibernate entity join is inaccessible", e);
        }
    }
}
