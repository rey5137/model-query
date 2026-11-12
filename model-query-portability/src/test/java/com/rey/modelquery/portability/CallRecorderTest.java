package com.rey.modelquery.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.portability.CallRecorder.FunctionMarker;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.example.calls.Caller;
import org.junit.jupiter.api.Test;

/** R-INS-14: what a wrapped executor records, and how a recorded call replays. */
class CallRecorderTest {

    private static final TableField<Object, Object> ROOT = TableField.root(Object.class);
    private static final ColumnField<Long, Object, Long> ID = ColumnField.of(Long.class, ROOT, "id", Long.class);
    private static final ColumnField<Long, Object, String> NAME =
            ColumnField.of(Long.class, ROOT, "name", String.class);
    private static final ModelQuery<Object, Long, Long> QUERY = ModelQuery.builder(ROOT, row -> row.get(ID))
            .select(SelectSet.of(ID)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc()).build();
    private static final ModelUpdate<Object, ?> RENAME = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID))
            .set(NAME, "renamed").where(f -> f.eq(ID, 1L)).build();
    private static final ModelUpdate<Object, ?> RENAME_IN_CHUNKS = ModelUpdate.builder(ROOT)
            .primaryKey(PrimaryKey.of(ID)).set(NAME, "renamed").where(f -> f.eq(ID, 1L))
            .chunked(ChunkOptions.size(10).commitEachChunk()).build();

    private final List<Invocation> invocations = new ArrayList<>();
    private final List<Object> drawn = new ArrayList<>();

    record Invocation(Method method, Object[] args) {}

    @Test
    void ac_ins_14_overloads_are_told_apart_by_method() {
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(null));
        Caller.search(() -> executor.one(QUERY));
        Caller.search(() -> executor.one(QUERY, 1L));

        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(call -> call.method().getName()).containsExactly("one", "one");
        assertThat(calls).extracting(call -> call.method().getParameterCount()).containsExactly(1, 2);
        assertThat(calls.get(0).arguments()).containsExactly(QUERY);
        assertThat(calls.get(1).arguments()).containsExactly(QUERY, 1L);
        assertThat(calls).extracting(RecordedCall::root).containsOnly(Object.class);
        assertThat(invocations).hasSize(2);
    }

    @Test
    void ac_ins_14_a_collection_argument_is_an_unmodifiable_copy_with_its_nulls() {
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(null));
        List<Long> keys = new ArrayList<>(Arrays.asList(1L, null, 2L));
        Set<Long> set = new LinkedHashSet<>(Arrays.asList(3L, null));
        Caller.search(() -> executor.byKeys(QUERY, keys));
        Caller.report(() -> executor.byKeys(QUERY, set));
        keys.add(4L);
        set.add(5L);

        List<RecordedCall> calls = recorder.calls();
        Object listCopy = calls.get(0).arguments().get(1);
        assertThat(listCopy).isInstanceOf(List.class).isEqualTo(Arrays.asList(1L, null, 2L));
        assertThat(calls.get(1).arguments().get(1)).isInstanceOf(Set.class).isEqualTo(new LinkedHashSet<>(
                Arrays.asList(3L, null)));
        @SuppressWarnings("unchecked")
        Collection<Object> copy = (Collection<Object>) listCopy;
        assertThatThrownBy(() -> copy.add(9L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> calls.get(0).arguments().set(0, null))
                .isInstanceOf(UnsupportedOperationException.class);
        // The application's own collection reached the executor, not the copy.
        assertThat(invocations.get(0).args()[1]).isSameAs(keys);
    }

    @Test
    void ac_ins_14_a_function_argument_is_a_marker_and_replays_as_the_harness_stand_in() {
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(null));
        AtomicInteger applicationRuns = new AtomicInteger();
        for (int i = 0; i < 2; i++) { // a new closure per call: still one record
            Caller.search(() -> executor.stream(QUERY, Limit.unlimited(), rows -> applicationRuns.incrementAndGet()));
        }
        Caller.report(() -> executor.export(QUERY, ExportOptions.defaults(), page -> page,
                row -> applicationRuns.incrementAndGet()));

        assertThat(applicationRuns).hasValue(2);
        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).hasSize(2);
        RecordedCall stream = calls.get(0);
        assertThat(stream.times()).isEqualTo(2);
        assertThat(stream.arguments().get(2)).isEqualTo(new FunctionMarker(Function.class, 2))
                .hasToString("<Function argument 2>");
        assertThat(calls.get(1).arguments().subList(2, 4))
                .containsExactly(new FunctionMarker(Function.class, 2), new FunctionMarker(Consumer.class, 3));

        // Replay hands the harness's functions to the executor: one draws a stream's first row, one is the identity,
        // and the sink does nothing.
        invocations.clear();
        ModelQueryExecutor<Object> harness = fake(null);
        assertThat(stream.replay(harness)).isNull();
        assertThat(drawn).containsExactly(1L);
        calls.get(1).replay(harness);
        assertThat(invocations).extracting(invocation -> invocation.method().getName())
                .containsExactly("stream", "export");
        @SuppressWarnings("unchecked")
        Function<Object, Object> transformer = (Function<Object, Object>) invocations.get(1).args()[2];
        List<Integer> page = List.of(1, 2);
        assertThat(transformer.apply(page)).isSameAs(page);
        @SuppressWarnings("unchecked")
        Consumer<Object> sink = (Consumer<Object>) invocations.get(1).args()[3];
        sink.accept("row");
        assertThat(applicationRuns).hasValue(2);
    }

    @Test
    void ac_ins_14_the_call_site_is_the_first_frame_outside_the_library() {
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(null));
        Caller.search(() -> executor.list(QUERY, Limit.unlimited()));

        RecordedCall call = recorder.calls().get(0);
        assertThat(call.callSite()).isEqualTo("Caller.search:" + Caller.searchLine());
        assertThat(call.label()).isEqualTo("Caller.search:" + Caller.searchLine() + " list").isEqualTo(call.toString());
    }

    @Test
    void ac_ins_14_equal_calls_from_one_call_site_are_one_record_with_its_times() {
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(null));
        for (int i = 0; i < 3; i++) {
            Caller.search(() -> executor.list(QUERY, Limit.of(10)));
        }
        Caller.report(() -> executor.list(QUERY, Limit.of(10)));
        Caller.search(() -> executor.list(QUERY, Limit.of(20)));

        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).extracting(RecordedCall::label).containsExactly(
                "Caller.search:" + Caller.searchLine() + " list x3", "Caller.report:" + Caller.reportLine() + " list",
                "Caller.search:" + Caller.searchLine() + " list");
        assertThat(calls).extracting(RecordedCall::times).containsExactly(3, 1, 1);
        assertThat(invocations).hasSize(5);
    }

    @Test
    void ac_ins_14_past_the_bound_calls_are_counted_not_kept() {
        CallRecorder recorder = CallRecorder.create(1);
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(null));
        Caller.search(() -> executor.list(QUERY, Limit.of(10)));
        Caller.search(() -> executor.list(QUERY, Limit.of(20)));
        Caller.search(() -> executor.list(QUERY, Limit.of(30)));
        Caller.search(() -> executor.list(QUERY, Limit.of(10))); // the kept record still counts its times

        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).singleElement().extracting(RecordedCall::times).isEqualTo(2);
        assertThat(((CallRecorder.Snapshot) calls).tally().lines()).containsExactly("recording: 2 calls past the "
                + "bound of 1 not kept; record with CallRecorder.create(maxCalls) and a larger bound");
        assertThat(calls.get(0).tally()).isSameAs(((CallRecorder.Snapshot) calls).tally());
        assertThat(invocations).hasSize(4);

        recorder.clear();
        assertThat(recorder.calls()).isEmpty();
        assertThat(((CallRecorder.Snapshot) recorder.calls()).tally().lines()).isEmpty();
        assertThatThrownBy(() -> CallRecorder.create(-1)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("maxCalls is -1");
    }

    @Test
    void ac_ins_14_a_failed_recording_is_counted_and_the_call_still_delegates() {
        CallRecorder recorder = CallRecorder.create();
        Map<Long, Long> found = Map.of(1L, 1L);
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(found));
        Collection<Long> uncopyable = new AbstractCollection<>() {
            @Override
            public Iterator<Long> iterator() {
                return List.of(1L).iterator();
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public Object[] toArray() {
                throw new IllegalStateException("cannot copy");
            }
        };

        assertThat(Caller.search(() -> executor.byKeys(QUERY, uncopyable))).isSameAs(found);
        assertThat(invocations).hasSize(1);
        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).isEmpty();
        assertThat(((CallRecorder.Snapshot) calls).tally().lines()).containsExactly(
                "recording: 1 call failed to record, the first with java.lang.IllegalStateException: cannot copy");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_ins_14_a_stack_overflow_while_recording_is_counted_and_other_vm_errors_propagate() {
        CallRecorder recorder = CallRecorder.create();
        Map<Long, Long> found = Map.of(1L, 1L);
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(found));
        Object recursive = new Object() {
            @Override
            public int hashCode() {
                return hashCode() + 1; // an argument's recursive hashCode, as a cyclic graph has
            }
        };

        assertThat(Caller.search(() -> executor.byKeys(QUERY, (Collection) List.of(recursive)))).isSameAs(found);
        assertThat(invocations).hasSize(1);
        assertThat(((CallRecorder.Snapshot) recorder.calls()).tally().lines()).containsExactly(
                "recording: 1 call failed to record, the first with java.lang.StackOverflowError");

        OutOfMemoryError exhausted = new OutOfMemoryError("exhausted");
        Object exhausting = new Object() {
            @Override
            public int hashCode() {
                throw exhausted;
            }
        };
        assertThatThrownBy(() -> Caller.search(() -> executor.byKeys(QUERY, (Collection) List.of(exhausting))))
                .isSameAs(exhausted);
        assertThat(invocations).hasSize(1);
    }

    @Test
    void ac_ins_14_the_wrapped_executor_rethrows_unchanged_after_recording() {
        RuntimeException stop = new RuntimeException("stop");
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(stop));
        assertThatThrownBy(() -> Caller.search(() -> executor.count(QUERY))).isSameAs(stop);
        assertThat(recorder.calls()).extracting(RecordedCall::label)
                .containsExactly("Caller.search:" + Caller.searchLine() + " count");
    }

    @Test
    void ac_ins_14_proxy_object_methods_use_identity_without_recording_or_delegating() {
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(null));
        ModelQueryExecutor<Object> other = recorder.wrap(Object.class, fake(null));
        assertThat(executor.equals(executor)).isTrue();
        assertThat(executor.equals(other)).isFalse();
        assertThat(executor.hashCode()).isEqualTo(System.identityHashCode(executor));
        assertThat(executor.toString()).contains("@");
        assertThat(recorder.calls()).isEmpty();
        assertThat(invocations).isEmpty();
    }

    @Test
    void ac_ins_14_the_two_argument_wrap_replays_a_commit_each_chunk_write_outside_a_transaction() {
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<Object> executor = recorder.wrap(Object.class, fake(null));
        Caller.search(() -> executor.update(RENAME));
        Caller.search(() -> executor.update(RENAME_IN_CHUNKS));

        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).extracting(RecordedCall::inTransaction).containsOnly(Optional.empty());
        assertThat(calls).extracting(RecordedCall::outsideTransaction).containsExactly(false, true);
    }

    /**
     * A delegate that logs each invocation and returns {@code result} when it fits the return type, throws it when it
     * is a {@code RuntimeException}, and otherwise returns the type's empty value; {@code stream} applies its body to
     * two rows, logging each one drawn.
     */
    @SuppressWarnings("unchecked")
    private ModelQueryExecutor<Object> fake(Object result) {
        return (ModelQueryExecutor<Object>) Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                new Class<?>[] {ModelQueryExecutor.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        throw new AssertionError("Object method delegated: " + method.getName());
                    }
                    invocations.add(new Invocation(method, args));
                    if (result instanceof RuntimeException thrown) {
                        throw thrown;
                    }
                    if (method.getName().equals("stream")) {
                        return ((Function<Object, Object>) args[2]).apply(Stream.of(1L, 2L).peek(drawn::add));
                    }
                    Class<?> type = method.getReturnType();
                    if (result != null && type.isInstance(result)) {
                        return result;
                    }
                    return type == long.class ? 0L : type == Optional.class ? Optional.empty()
                            : type == List.class ? List.of() : type == Map.class ? Map.of() : null;
                });
    }
}
