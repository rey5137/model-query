package org.example.orders;

import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An application's JDK-proxy decorator that counts each call, then delegates, outside the library's packages: its
 * {@code invoke} is never the call site a recorder it wraps reads (R-INS-14, R-SPR-18).
 */
public final class TimingHandler implements InvocationHandler {

    private final Object delegate;
    private final AtomicInteger calls;

    private TimingHandler(Object delegate, AtomicInteger calls) {
        this.delegate = delegate;
        this.calls = calls;
    }

    /** {@code executor} behind a JDK proxy counting each call in {@code calls}. */
    @SuppressWarnings("unchecked")
    public static <E> ModelQueryExecutor<E> wrap(ModelQueryExecutor<E> executor, AtomicInteger calls) {
        return (ModelQueryExecutor<E>) Proxy.newProxyInstance(TimingHandler.class.getClassLoader(),
                new Class<?>[] { ModelQueryExecutor.class }, new TimingHandler(executor, calls));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "TimingHandler over " + delegate;
            };
        }
        calls.incrementAndGet();
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
