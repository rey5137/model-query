package com.rey.modelquery.spike.rfc0023;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.spike.rfc0023.Events.Event;
import com.rey.modelquery.spike.rfc0023.Events.Range;
import com.rey.modelquery.spike.rfc0023.Events.Row;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What the engine would run for a {@code KeyGroups} (RFC 0023 §2, §3): the keys grouped by {@code group(key)} in
 * first-seen order, every group split into rounds, each round the key {@code IN} filter plus the group's bound range.
 * {@code byKeys} rounds go through the real {@code executor.byKeys} (so its own chunking, R-EXE-13, applies); child
 * rounds are {@code list} reads chunked here.
 */
final class Emulator {
    enum Form { TWO_SIDED, LOWER, NONE }

    private final ModelQueryExecutor<Event> ex;

    Emulator(ModelQueryExecutor<Event> ex) {
        this.ex = ex;
    }

    ModelQueryExecutor<Event> executor() {
        return ex;
    }

    static <K, G> Map<G, List<K>> group(Collection<K> keys, Function<? super K, ? extends G> group) {
        Map<G, List<K>> groups = new LinkedHashMap<>();
        for (K key : new LinkedHashSet<>(keys)) {
            groups.computeIfAbsent(group.apply(key), g -> new ArrayList<>()).add(key);
        }
        return groups;
    }

    Map<Long, Row> byKeysUngrouped(Collection<Long> keys) {
        return ex.byKeys(Events.NONE.build(), keys);
    }

    /** {@code verifyAll} null is {@code assumeInRange()}. */
    Map<Long, Row> byKeysGrouped(Form form, Collection<Long> keys, Function<Long, Range> group, Range verifyAll) {
        Map<Range, List<Long>> groups = group(keys, group);
        Map<Long, Row> out = new LinkedHashMap<>();
        for (Map.Entry<Range, List<Long>> g : groups.entrySet()) {
            out.putAll(ex.byKeys(keyed(form, g.getKey()), g.getValue()));
        }
        if (verifyAll != null) {
            List<Long> missing = new ArrayList<>();
            for (Long key : new LinkedHashSet<>(keys)) {
                if (!out.containsKey(key)) {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                out.putAll(ex.byKeys(keyed(form, verifyAll), missing));
            }
        }
        return out;
    }

    private static com.rey.modelquery.core.ModelQuery<Event, Long, Row> keyed(Form form, Range r) {
        return switch (form) {
            case TWO_SIDED -> Events.TWO_SIDED.bind(Events.FROM, r.from()).bind(Events.TO, r.to()).build();
            case LOWER -> Events.LOWER.bind(Events.FROM, r.from()).build();
            case NONE -> Events.NONE.build();
        };
    }

    List<Row> childUngrouped(Collection<Long> orderIds, int round) {
        List<Row> out = new ArrayList<>();
        for (List<Long> chunk : chunks(new ArrayList<>(new LinkedHashSet<>(orderIds)), round)) {
            out.addAll(ex.list(Events.childNone(chunk), Limit.unlimited()));
        }
        return out;
    }

    List<Row> childGrouped(Form form, Collection<Long> orderIds, Function<Long, Range> group, int round) {
        Map<Range, List<Long>> groups = group(orderIds, group);
        List<Row> out = new ArrayList<>();
        for (Map.Entry<Range, List<Long>> g : groups.entrySet()) {
            for (List<Long> chunk : chunks(g.getValue(), round)) {
                out.addAll(ex.list(form == Form.LOWER ? Events.childLower(chunk, g.getKey())
                        : Events.childTwoSided(chunk, g.getKey()), Limit.unlimited()));
            }
        }
        return out;
    }

    static <T> List<List<T>> chunks(List<T> all, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return out;
    }
}
