package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The rules every executor proxy of the check follows (D-222): {@code Object} methods use the proxy's identity without
 * delegation or recording, a default method runs through {@code invokeDefault}, and a delegated throwable is rethrown
 * unchanged. Subclasses handle the executor's own methods.
 *
 * @implSpec R-INS-11, R-INS-14
 */
@Incubating
public abstract class ExecutorHandler implements InvocationHandler {

    /** A handler; {@link #executor()} makes its proxies. */
    protected ExecutorHandler() {}

    /** A new executor proxy over this handler. */
    @SuppressWarnings("unchecked")
    public final <E> ModelQueryExecutor<E> executor() {
        return (ModelQueryExecutor<E>) Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                new Class<?>[] {ModelQueryExecutor.class}, this);
    }

    @Override
    public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> proxy.getClass().getName() + "@"
                        + Integer.toHexString(System.identityHashCode(proxy));
                default -> throw new IllegalStateException("Unexpected Object method: " + method.getName());
            };
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        return handle(method, args);
    }

    /** Handles an abstract {@code ModelQueryExecutor} method; {@code args} is as the proxy received it. */
    protected abstract Object handle(Method method, Object[] args) throws Throwable;

    /** Invokes {@code method} on {@code target}, rethrowing what it throws unchanged. */
    protected static Object delegate(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }
}
