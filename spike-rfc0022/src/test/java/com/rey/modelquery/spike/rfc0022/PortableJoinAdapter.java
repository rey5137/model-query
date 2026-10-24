package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.EntityJoinRenderer;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Employee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.Map;

/** Replaces only creation of Invoice.rep with the proposed column join; the executor remains production code. */
final class PortableJoinAdapter {
    private PortableJoinAdapter() {}

    static EntityManager wrap(EntityManager em, EntityJoinRenderer renderer) {
        Map<Object, Object> originals = new IdentityHashMap<>();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaBuilder builder = proxy(CriteriaBuilder.class, cb, originals, (method, args) -> {
            Object result = invoke(cb, method, unwrap(args, originals));
            if (result instanceof CriteriaQuery<?> query) {
                return proxy(CriteriaQuery.class, query, originals, (queryMethod, queryArgs) -> {
                    Object value = invoke(query, queryMethod, unwrap(queryArgs, originals));
                    if (value instanceof Root<?> root) {
                        return proxy(Root.class, root, originals, (rootMethod, rootArgs) -> {
                            if (rootMethod.getName().equals("join") && rootArgs != null
                                    && rootArgs.length == 2 && "rep".equals(rootArgs[0])) {
                                JoinKind kind = rootArgs[1] == JoinType.LEFT ? JoinKind.LEFT : JoinKind.INNER;
                                Join<?, ?> joined = (Join<?, ?>) renderer.join(root, Employee.class, kind,
                                        candidate -> cb.equal(candidate.get("code"), root.get("repCode")));
                                Predicate key = joined.getOn();
                                // An association's implicit key survives on(); our explicit replacement must too.
                                return proxy(Join.class, joined, originals, (joinMethod, joinArgs) -> {
                                    if (joinMethod.getName().equals("on")) {
                                        Predicate extra = joinArgs[0] instanceof Predicate[] predicates
                                                ? cb.and(predicates) : cb.isTrue(cast(joinArgs[0]));
                                        joined.on(cb.and(key, extra));
                                        return joined;
                                    }
                                    return invoke(joined, joinMethod, unwrap(joinArgs, originals));
                                });
                            }
                            return invoke(root, rootMethod, unwrap(rootArgs, originals));
                        });
                    }
                    return value;
                });
            }
            return result;
        });
        return proxy(EntityManager.class, em, originals, (method, args) -> method.getName().equals("getCriteriaBuilder")
                ? builder : invoke(em, method, unwrap(args, originals)));
    }

    @SuppressWarnings("unchecked")
    private static jakarta.persistence.criteria.Expression<Boolean> cast(Object expression) {
        return (jakarta.persistence.criteria.Expression<Boolean>) expression;
    }

    @FunctionalInterface
    private interface Call {
        Object apply(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Object original, Map<Object, Object> originals, Call call) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> call.apply(method, args));
        originals.put(proxy, original);
        return type.cast(proxy);
    }

    private static Object[] unwrap(Object[] args, Map<Object, Object> originals) {
        if (args == null) {
            return null;
        }
        Object[] result = args.clone();
        for (int i = 0; i < result.length; i++) {
            result[i] = originals.getOrDefault(result[i], result[i]);
        }
        return result;
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
