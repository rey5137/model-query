package com.rey.modelquery.portability;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.portability.internal.ExecutorHandler;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

/**
 * Records the calls an application makes on its executors, to replay each on every target with
 * {@link PortabilityCheck#replay(Collection)}. A {@linkplain #wrap(Class, ModelQueryExecutor) wrapped} executor
 * records each invocation, then delegates: its root, the {@code ModelQueryExecutor} method, the arguments (a collection
 * or map copied, a function kept as a marker of its parameter), the application's call site and, with an
 * {@code EntityManager}, whether the call ran in a transaction. Equal calls are one record with a count. Past the
 * bound, calls are counted, not kept; a recording that fails is counted and never fails the call. Either count fails
 * the check that replays the calls.
 *
 * <p>Shared and thread-safe, so calls on server threads are recorded too. Add the module in test scope.
 *
 * @implSpec R-INS-14
 */
@Incubating
public final class CallRecorder {

    private static final int DEFAULT_MAX_CALLS = 10_000;
    private static final StackWalker WALKER = StackWalker.getInstance();
    // The library, Spring's proxies and the JDK's reflection and proxy frames: the call site is the first frame past.
    private static final List<String> NOT_CALL_SITES = List.of("com.rey.modelquery.", "org.springframework.",
            "java.lang.reflect.", "java.lang.invoke.", "jdk.internal.reflect.", "jdk.proxy", "com.sun.proxy.");

    private final int maxCalls;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private long dropped;
    private long failed;
    private Throwable firstFailure;
    private long snapshots;

    private CallRecorder(int maxCalls) {
        this.maxCalls = maxCalls;
    }

    /** A recorder keeping at most 10,000 distinct calls. */
    public static CallRecorder create() {
        return new CallRecorder(DEFAULT_MAX_CALLS);
    }

    /**
     * A recorder keeping at most {@code maxCalls} distinct calls; later ones are counted, and the count fails the
     * check that replays them.
     *
     * @throws AssertionError when {@code maxCalls} is negative
     */
    public static CallRecorder create(int maxCalls) {
        if (maxCalls < 0) {
            throw new AssertionError("CallRecorder.create(maxCalls): maxCalls is " + maxCalls + ", not at least 0");
        }
        return new CallRecorder(maxCalls);
    }

    /**
     * {@code executor}, recording each call on it as a call on {@code root}. A {@code commitEachChunk()} write
     * replays outside a transaction, every other call in one.
     */
    public <E> ModelQueryExecutor<E> wrap(Class<E> root, ModelQueryExecutor<E> executor) {
        return new Recording(Objects.requireNonNull(root, "root"), Objects.requireNonNull(executor, "executor"),
                null).executor();
    }

    /**
     * {@code executor}, recording each call on it as a call on {@code root}, with whether {@code em} was joined to a
     * transaction at the call; the call replays in or outside a transaction to match.
     */
    public <E> ModelQueryExecutor<E> wrap(Class<E> root, ModelQueryExecutor<E> executor, EntityManager em) {
        return new Recording(Objects.requireNonNull(root, "root"), Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(em, "em")).executor();
    }

    /** A snapshot of the recorded calls, in first-seen order, with the counts of calls not kept. */
    public synchronized List<RecordedCall> calls() {
        Tally tally = new Tally(this, ++snapshots, maxCalls, dropped, failed,
                firstFailure == null ? null : firstFailure.toString());
        List<RecordedCall> calls = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            Key key = entry.key;
            calls.add(new RecordedCall(key.root, key.method, key.arguments, key.callSite, entry.times,
                    key.inTransaction, tally));
        }
        return new Snapshot(calls, tally);
    }

    /** Forgets every recorded call and both counts. */
    public synchronized void clear() {
        entries.clear();
        dropped = 0;
        failed = 0;
        firstFailure = null;
    }

    private void record(Class<?> root, Method method, Object[] args, EntityManager em) {
        try {
            Object[] arguments = args == null ? new Object[0] : args.clone();
            Class<?>[] parameters = method.getParameterTypes();
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = copy(parameters[i], i, arguments[i]);
            }
            Key key = new Key(root, method, Collections.unmodifiableList(Arrays.asList(arguments)), callSite(),
                    em == null ? null : em.isJoinedToTransaction());
            synchronized (this) {
                Entry entry = entries.get(key);
                if (entry != null) {
                    entry.times++;
                } else if (entries.size() < maxCalls) {
                    entries.put(key, new Entry(key));
                } else {
                    dropped++;
                }
            }
        } catch (StackOverflowError failure) { // an argument's recursive hashCode or equals: counted like any other
            countFailure(failure);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) { // a recording never fails the application's call: it is counted instead
            countFailure(failure);
        }
    }

    private synchronized void countFailure(Throwable failure) {
        failed++;
        if (firstFailure == null) {
            firstFailure = failure;
        }
    }

    /** {@code argument} as recorded: a function as a marker, a collection or map as an unmodifiable copy. */
    private static Object copy(Class<?> parameter, int index, Object argument) {
        if (argument != null && parameter.isInterface() && parameter.isAnnotationPresent(FunctionalInterface.class)) {
            return new FunctionMarker(parameter, index);
        }
        if (argument instanceof Set<?> set) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(set));
        }
        if (argument instanceof Collection<?> collection) {
            return Collections.unmodifiableList(new ArrayList<>(collection));
        }
        if (argument instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(map));
        }
        return argument;
    }

    /** The first frame outside the library, Spring and the JDK's reflection, as {@code Class.method:line}. */
    private static String callSite() {
        StackWalker.StackFrame frame = WALKER.walk(frames -> frames
                        .filter(f -> NOT_CALL_SITES.stream().noneMatch(f.getClassName()::startsWith)).findFirst())
                .orElseThrow(() -> new IllegalStateException("no call site: every stack frame is the library's, "
                        + "Spring's or the JDK's reflection"));
        String className = frame.getClassName();
        String site = className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
        return frame.getLineNumber() >= 0 ? site + ":" + frame.getLineNumber() : site;
    }

    /** What makes two calls one record: equal arguments by {@code equals}, functions by parameter (R-INS-14). */
    private record Key(Class<?> root, Method method, List<Object> arguments, String callSite, Boolean inTransaction) {}

    private static final class Entry {
        private final Key key;
        private int times = 1;

        private Entry(Key key) {
            this.key = key;
        }
    }

    /**
     * The counts of {@code recorder} as its {@code sequence}-th snapshot saw them; each non-zero count is a failing
     * report line. A check keeps one tally per recorder, the latest snapshot's.
     */
    record Tally(CallRecorder recorder, long sequence, int maxCalls, long dropped, long failed, String firstFailure) {

        List<String> lines() {
            List<String> lines = new ArrayList<>();
            if (dropped > 0) {
                lines.add("recording: " + calls(dropped) + " past the bound of " + maxCalls + " not kept; record "
                        + "with CallRecorder.create(maxCalls) and a larger bound");
            }
            if (failed > 0) {
                lines.add("recording: " + calls(failed) + " failed to record, the first with " + firstFailure);
            }
            return lines;
        }

        private static String calls(long count) {
            return count + (count == 1 ? " call" : " calls");
        }
    }

    /** The list {@link #calls()} returns: unmodifiable, and carrying the counts to the check that replays it. */
    static final class Snapshot extends AbstractList<RecordedCall> implements RandomAccess {

        private final List<RecordedCall> calls;
        private final Tally tally;

        private Snapshot(List<RecordedCall> calls, Tally tally) {
            this.calls = calls;
            this.tally = tally;
        }

        Tally tally() {
            return tally;
        }

        @Override
        public RecordedCall get(int index) {
            return calls.get(index);
        }

        @Override
        public int size() {
            return calls.size();
        }
    }

    /** A function argument recorded as a marker of its parameter: replay passes the harness's own stand-in. */
    record FunctionMarker(Class<?> type, int index) {

        @Override
        public String toString() {
            return "<" + type.getSimpleName() + " argument " + index + ">";
        }
    }

    private final class Recording extends ExecutorHandler {

        private final Class<?> root;
        private final Object delegate;
        private final EntityManager em;

        private Recording(Class<?> root, Object delegate, EntityManager em) {
            this.root = root;
            this.delegate = delegate;
            this.em = em;
        }

        @Override
        protected Object handle(Method method, Object[] args) throws Throwable {
            record(root, method, args, em);
            return delegate(delegate, method, args);
        }
    }
}
