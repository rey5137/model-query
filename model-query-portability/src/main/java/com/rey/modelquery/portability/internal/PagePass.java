package com.rey.modelquery.portability.internal;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.vendor.InlineCountFallback;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Per-pass page observation and the second pass's single NO_COUNT rewrite (D-222). */
final class PagePass implements InvocationHandler {

    private final Object delegate;
    private final JdbcRecording recording;
    private final int rewrite;
    private int pages;
    private int firstPage;
    private CallResult.Statement statement = CallResult.Statement.NONE;
    private CallResult.Statement content = CallResult.Statement.CONTENT;

    PagePass(Object delegate, JdbcRecording recording, int rewrite) {
        this.delegate = delegate;
        this.recording = recording;
        this.rewrite = rewrite;
    }

    @SuppressWarnings("unchecked")
    <E> ModelQueryExecutor<E> executor() {
        return (ModelQueryExecutor<E>) Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                new Class<?>[] {ModelQueryExecutor.class}, this);
    }

    int countedPage() {
        return statement == CallResult.Statement.COUNT ? firstPage : 0;
    }

    CallResult.Statement statement() {
        return statement;
    }

    CallResult.Statement content() {
        return content;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
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
        if (!method.getName().equals("page") || args.length != 3 || !(args[2] instanceof CountMode mode)) {
            return delegate(method, args);
        }
        int page = ++pages;
        boolean eligible = recording.statements().isEmpty() && firstPage == 0
                && (mode == CountMode.COUNT || mode == CountMode.INLINE_COUNT);
        Boolean[] fallback = {null};
        Object[] actual = args.clone();
        if (page == rewrite) {
            actual[2] = CountMode.NO_COUNT;
        }
        try {
            if (eligible && mode == CountMode.INLINE_COUNT && page != rewrite) {
                // The observer sees the executor's built statements, including a customizer's DISTINCT.
                Object[] result = {null};
                Throwable[] failure = {null};
                InlineCountFallback.observe(value -> fallback[0] = value, () -> {
                    try {
                        result[0] = delegate(method, actual);
                    } catch (Throwable thrown) {
                        failure[0] = thrown;
                    }
                });
                if (failure[0] != null) {
                    throw failure[0];
                }
                return result[0];
            }
            return delegate(method, actual);
        } finally {
            if (eligible && !recording.statements().isEmpty()) {
                firstPage = page;
                ModelQuery<?, ?, ?> query = (ModelQuery<?, ?, ?>) args[0];
                PageSpec spec = (PageSpec) args[1];
                content = query.primaryKeyFirst().filter(keys -> spec.offset() > keys.offsetThreshold()).isPresent()
                        ? CallResult.Statement.KEYS : CallResult.Statement.CONTENT;
                boolean count = mode == CountMode.COUNT || Boolean.TRUE.equals(fallback[0]);
                if (count) {
                    statement = CallResult.Statement.COUNT;
                } else if (!count && Boolean.FALSE.equals(fallback[0])) {
                    statement = CallResult.Statement.CONTENT_AND_COUNT;
                }
            }
        }
    }

    private Object delegate(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }
}
