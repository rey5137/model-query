package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * RFC 0025 Part C's not-API engine entry, emulated by reflection on the production executor: nothing here is a copy
 * of engine code. {@link #childrenOf} is production's private {@code childrenOf(load, q, keys)}, key groups and all;
 * {@link #oneRun} runs one {@code KeyRun} of given values through production's {@code ChildRounds} (its
 * {@code size} then {@code read}, as {@code childrenOf} does), which is the RFC's key-group rule.
 */
final class Engine {
    private static final Class<?> EXECUTOR = type("com.rey.modelquery.jpa.DefaultModelQueryExecutor");
    private static final Class<?> ROUNDS = type("com.rey.modelquery.jpa.DefaultModelQueryExecutor$ChildRounds");
    private static final Class<?> KEY_RUN = type("com.rey.modelquery.jpa.DefaultModelQueryExecutor$KeyRun");

    private Engine() {}

    /** Production's {@code childrenOf(load, load.query(), keys)} on {@code executor}. */
    static Object childrenOf(ModelQueryExecutor<?> executor, ChildLoad<?, ?> load, List<Object> keys) {
        return invoke(method(EXECUTOR, "childrenOf", ChildLoad.class, ModelQuery.class, List.class), executor, load,
                load.query(), keys);
    }

    /**
     * One run of {@code keys} with {@code values} bound, through production's {@code checkFirstRun(q)} and a fresh
     * {@code ChildRounds}: its {@code size(run)} builds the first round, its {@code read(run)} prepares it.
     */
    static void oneRun(ModelQueryExecutor<?> executor, ChildLoad<?, ?> load, List<Object> keys,
            Map<QueryParam<?>, Object> values) {
        invoke(method(EXECUTOR, "checkFirstRun", ModelQuery.class), executor, load.query());
        EntityManager em = (EntityManager) field(executor, "em");
        try {
            Constructor<?> roundsCtor = ROUNDS.getDeclaredConstructor(EXECUTOR, ChildLoad.class, ModelQuery.class,
                    jakarta.persistence.criteria.CriteriaBuilder.class);
            roundsCtor.setAccessible(true);
            Object rounds = roundsCtor.newInstance(executor, load, load.query(), em.getCriteriaBuilder());
            Constructor<?> runCtor = KEY_RUN.getDeclaredConstructor(List.class, Map.class);
            runCtor.setAccessible(true);
            Object run = runCtor.newInstance(List.copyOf(keys), values);
            invoke(method(ROUNDS, "size", KEY_RUN), rounds, run);
            invoke(method(ROUNDS, "read", KEY_RUN), rounds, run);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The executor's render options, to show which value handling a cell ran under. */
    static RenderOptions renderOptions(ModelQueryExecutor<?> executor) {
        return (RenderOptions) field(executor, "renderOptions");
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = EXECUTOR.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(cause);
    }
}
