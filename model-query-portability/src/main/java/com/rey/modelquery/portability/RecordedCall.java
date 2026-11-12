package com.rey.modelquery.portability;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.portability.CallRecorder.FunctionMarker;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.BaseStream;

/**
 * One distinct call a {@link CallRecorder} recorded: the root, the {@code ModelQueryExecutor} method, the arguments
 * with each function argument as a marker of its parameter, the application's call site, how many times it was made
 * and, with the three-argument {@code wrap}, whether it ran in a transaction. Created only by a recorder.
 *
 * @implSpec R-INS-14
 */
@Incubating
public final class RecordedCall {

    private final Class<?> root;
    private final Method method;
    private final List<Object> arguments;
    private final String callSite;
    private final int times;
    private final Boolean inTransaction;
    private final CallRecorder.Tally tally;

    RecordedCall(Class<?> root, Method method, List<Object> arguments, String callSite, int times,
            Boolean inTransaction, CallRecorder.Tally tally) {
        this.root = root;
        this.method = method;
        this.arguments = arguments;
        this.callSite = callSite;
        this.times = times;
        this.inTransaction = inTransaction;
        this.tally = tally;
    }

    /** The root entity of the executor called. */
    public Class<?> root() {
        return root;
    }

    /** The {@code ModelQueryExecutor} method invoked, which tells overloads apart. */
    public Method method() {
        return method;
    }

    /** The arguments, unmodifiable, nulls kept, a collection or map as a copy and a function as a marker. */
    public List<Object> arguments() {
        return arguments;
    }

    /** The application's call site, {@code Class.method:line}. */
    public String callSite() {
        return callSite;
    }

    /** How many times the call was made. */
    public int times() {
        return times;
    }

    /** Whether the call ran in a transaction; empty unless recorded through the three-argument {@code wrap}. */
    public Optional<Boolean> inTransaction() {
        return Optional.ofNullable(inTransaction);
    }

    /** The replay's label: {@code callSite method}, then {@code xN} when made {@code N > 1} times. */
    public String label() {
        return label(site(), times);
    }

    @Override
    public String toString() {
        return label();
    }

    static String label(String site, int times) {
        return times > 1 ? site + " x" + times : site;
    }

    /** {@code callSite method}: the part of the label that identical report lines share. */
    String site() {
        return callSite + " " + method.getName();
    }

    CallRecorder.Tally tally() {
        return tally;
    }

    /**
     * Whether the call replays with no transaction: as recorded, or, recorded without the transaction state, for a
     * {@code commitEachChunk()} write only.
     */
    boolean outsideTransaction() {
        if (inTransaction != null) {
            return !inTransaction;
        }
        return arguments.stream().map(RecordedCall::chunkOptions).flatMap(Optional::stream)
                .anyMatch(ChunkOptions::commitsEachChunk);
    }

    /** Runs the call on {@code executor}, each function marker replaced by the harness's stand-in. */
    Object replay(ModelQueryExecutor<?> executor) {
        Object[] args = arguments.toArray();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof FunctionMarker marker) {
                args[i] = standIn(marker);
            }
        }
        try {
            return method.invoke(executor, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new UndeclaredThrowableException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(label() + ": cannot invoke " + method, e);
        }
    }

    /**
     * The harness's own function: a body drawing one element of a stream, an identity transformer, a no-op sink. The
     * stub stops at the first statement, so none changes what is rendered, and none reruns the application's code.
     */
    private Object standIn(FunctionMarker marker) {
        if (marker.type() == Function.class) {
            return (Function<Object, Object>) RecordedCall::drawOneOrSame;
        }
        if (marker.type() == Consumer.class) {
            return (Consumer<Object>) value -> { };
        }
        throw new IllegalStateException(label() + ": no replay stand-in for the " + marker.type().getName()
                + " argument " + marker.index());
    }

    private static Object drawOneOrSame(Object value) {
        if (value instanceof BaseStream<?, ?> stream) {
            Iterator<?> iterator = stream.iterator();
            if (iterator.hasNext()) {
                iterator.next();
            }
            return null;
        }
        return value;
    }

    private static Optional<ChunkOptions> chunkOptions(Object argument) {
        if (argument instanceof ModelUpdate<?, ?> update) {
            return update.chunkOptions();
        }
        if (argument instanceof ModelDelete<?, ?> delete) {
            return delete.chunkOptions();
        }
        if (argument instanceof ModelInsert<?, ?> insert) {
            return insert.chunkOptions();
        }
        return Optional.empty();
    }
}
