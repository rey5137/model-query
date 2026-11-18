package com.rey.modelquery.audit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Counts unselected reads independently of weakly held model instances. */
final class ReadEvents {
    record Key(Tag.ReadEvent read, String callSite, String method, String select) {}

    private static final ConcurrentHashMap<Key, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private ReadEvents() {}

    static void record(Tag tag, Tag.ReadEvent read) {
        Key key = new Key(read, tag.callSite(), tag.method(), String.valueOf(tag.select()));
        COUNTS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    static Map<Key, Long> snapshot() {
        return COUNTS.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().sum()));
    }
}
