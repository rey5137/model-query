package com.rey.modelquery.hibernate;

import jakarta.persistence.Query;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * An insert-values statement whose conflict target carries an arbiter predicate (R-WRT-52). Hibernate's criteria model
 * has no conflict-target predicate, so the provider cannot render one through it: the statement is Hibernate's own
 * mutation query, wrapped so that the {@code executeUpdate()} the engine calls runs the SQL Hibernate translates with
 * the predicate spliced in after the conflict target, while every other call (its parameters, its timeout hint, its
 * SQM statement, the session it belongs to) reaches the query itself. The wrapper implements every interface of the
 * wrapped query, so the casts the returning path makes ({@link HibernateMutations#rows}) see the same types and read
 * the predicate back off the wrapper; a call that returns the query itself, a fluent setter or an {@code unwrap} to a
 * type the wrapper implements, returns the wrapper, so the predicate is never lost on the way to execution.
 *
 * <p>A wrapper rather than an SPI method: {@code InsertSupport#insertValues} promises a query the engine runs with
 * {@code executeUpdate()} or hands to {@code ReturningSupport#rows}, and neither takes a predicate the criteria model
 * cannot hold. Running the translation, its binders, its timeout, its auto-flush and its cache invalidation is
 * {@link HibernateMutations}', shared with a returning write.
 *
 * @implSpec R-WRT-52
 */
final class ArbiterQuery implements InvocationHandler {

    private final Query statement;
    private final String arbiter;

    private ArbiterQuery(Query statement, String arbiter) {
        this.statement = statement;
        this.arbiter = arbiter;
    }

    /** {@code statement} wrapped so that its {@code executeUpdate()} runs the SQL with {@code arbiter} spliced in. */
    static Query of(Query statement, String arbiter) {
        // The statement's own loader sees every interface it implements, whichever loader holds this module.
        return (Query) Proxy.newProxyInstance(statement.getClass().getClassLoader(),
                interfaces(statement.getClass()), new ArbiterQuery(statement, arbiter));
    }

    /** The wrapper around {@code statement}, when it is one; empty for a statement that carries no arbiter. */
    static Optional<ArbiterQuery> of(Query statement) {
        return Proxy.isProxyClass(statement.getClass())
                        && Proxy.getInvocationHandler(statement) instanceof ArbiterQuery arbiter
                ? Optional.of(arbiter)
                : Optional.empty();
    }

    /** The arbiter predicate spliced into the statement's conflict target. */
    String arbiter() {
        return arbiter;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        int arity = method.getParameterCount();
        switch (method.getName()) {
            case "executeUpdate" -> {
                if (arity == 0) {
                    // the proxy, not the wrapped statement: the translation reads the arbiter back off it
                    return HibernateMutations.executeUpdate((Query) proxy);
                }
            }
            case "equals" -> {
                if (arity == 1) {
                    return proxy == args[0];
                }
            }
            case "hashCode" -> {
                if (arity == 0) {
                    return System.identityHashCode(proxy);
                }
            }
            case "unwrap" -> {
                if (arity == 1 && args[0] instanceof Class<?> type) {
                    // a type only the statement's own class has, such as its implementation class, gets the statement
                    return type.isInstance(proxy) ? proxy : delegate(method, args);
                }
            }
            default -> {
                // every other call reaches the statement
            }
        }
        Object result = delegate(method, args);
        return result == statement && method.getReturnType().isInstance(proxy) ? proxy : result;
    }

    /** {@code method} on the wrapped statement, its own exception rethrown as is. */
    private Object delegate(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(statement, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Every interface an instance of {@code type} implements, which the wrapper must implement too. */
    private static Class<?>[] interfaces(Class<?> type) {
        Set<Class<?>> all = new LinkedHashSet<>();
        for (Class<?> line = type; line != null; line = line.getSuperclass()) {
            collect(line, all);
        }
        return all.toArray(Class<?>[]::new);
    }

    private static void collect(Class<?> type, Set<Class<?>> all) {
        for (Class<?> api : type.getInterfaces()) {
            if (all.add(api)) {
                collect(api, all);
            }
        }
    }
}
