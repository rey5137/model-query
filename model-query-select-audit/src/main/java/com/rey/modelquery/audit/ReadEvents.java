package com.rey.modelquery.audit;

import com.rey.modelquery.core.SelectSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Counts reads, call-site selections, unmapped inherited reads and missing hooks across a test session. */
final class ReadEvents {
    record Key(Tag.ReadEvent read, String callSite, String method, String select) {}

    record CallSiteKey(String callSite, String method, String model, SelectSet<?> select, String selectLabel,
            List<String> requestedProperties) {
        CallSiteKey(String callSite, String method, String model, SelectSet<?> select, String selectLabel,
                List<String> requestedProperties) {
            this.callSite = callSite;
            this.method = method;
            this.model = model;
            this.select = select;
            this.selectLabel = selectLabel;
            this.requestedProperties = List.copyOf(requestedProperties);
        }
    }

    record InheritedKey(String model, String inherited, String reader, String callSite, String method) {}

    record MissingHookKey(String model, String callSite, String method) {}

    static final class CallSiteStats {
        final LongAdder calls = new LongAdder();
        final Set<String> tests = ConcurrentHashMap.newKeySet();
        final Set<String> readProperties = ConcurrentHashMap.newKeySet();
    }

    private static final ConcurrentHashMap<Key, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CallSiteKey, CallSiteStats> CALL_SITES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<InheritedKey, LongAdder> INHERITED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MissingHookKey, LongAdder> MISSING_HOOKS = new ConcurrentHashMap<>();

    private ReadEvents() {}

    static void record(Tag tag, Tag.ReadEvent read) {
        Key key = new Key(read, tag.callSite(), tag.method(), tag.selectLabel());
        COUNTS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    static Map<Key, Long> snapshot() {
        return COUNTS.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().sum()));
    }

    static CallSiteStats registerCallSite(Tag tag) {
        if (tag.requestedProperties().isEmpty()) {
            return null;
        }
        return CALL_SITES.computeIfAbsent(callSiteKey(tag), ignored -> new CallSiteStats());
    }

    static void recordCall(Tag tag, String test) {
        CallSiteStats stats = tag.callSiteStats();
        if (stats != null) {
            stats.calls.increment();
            if (test != null && !test.isEmpty()) {
                stats.tests.add(test);
            }
        }
    }

    static void recordInherited(Tag tag, String inherited, String reader) {
        InheritedKey key = new InheritedKey(tag.modelName(), inherited, reader, tag.callSite(), tag.method());
        INHERITED.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    static void recordMissingHook(String model, String callSite, String method) {
        MissingHookKey key = new MissingHookKey(model, callSite, method);
        MISSING_HOOKS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    static Map<CallSiteKey, CallSiteStats> callSites() {
        return Map.copyOf(CALL_SITES);
    }

    static Map<InheritedKey, Long> inheritedReads() {
        return INHERITED.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().sum()));
    }

    static Map<MissingHookKey, Long> missingHooks() {
        return MISSING_HOOKS.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().sum()));
    }

    static void clear() {
        COUNTS.clear();
        CALL_SITES.clear();
        INHERITED.clear();
        MISSING_HOOKS.clear();
        AuditObserver.clearMissingHooks();
    }

    private static CallSiteKey callSiteKey(Tag tag) {
        return new CallSiteKey(tag.callSite(), tag.method(), tag.modelName(), tag.select(), tag.selectLabel(),
                tag.requestedProperties());
    }
}
