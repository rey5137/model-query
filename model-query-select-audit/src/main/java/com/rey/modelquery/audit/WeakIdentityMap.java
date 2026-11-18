package com.rey.modelquery.audit;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A concurrent map with weak keys compared by object identity ({@code ==}), so models are never held strongly
 * and records compare by identity rather than component equality.
 */
final class WeakIdentityMap<K, V> {

    private final ConcurrentHashMap<Object, V> map = new ConcurrentHashMap<>();
    private final ReferenceQueue<K> queue = new ReferenceQueue<>();

    void put(K key, V value) {
        expunge();
        map.put(new WeakKey<>(key, queue), value);
    }

    V get(Object key) {
        return map.isEmpty() ? null : map.get(new Lookup(key));
    }

    V computeIfAbsent(K key, Supplier<V> factory) {
        V value = get(key);
        if (value == null) {
            value = factory.get();
            put(key, value);
        }
        return value;
    }

    boolean containsKey(Object key) {
        return get(key) != null;
    }

    int size() {
        expunge();
        return map.size();
    }

    void clear() {
        map.clear();
        while (queue.poll() != null) {}
    }

    private void expunge() {
        for (Object stale; (stale = queue.poll()) != null;) {
            map.remove(stale);
        }
    }

    private interface Key {
        Object referent();
    }

    private static final class WeakKey<K> extends WeakReference<K> implements Key {
        private final int hash;

        WeakKey(K key, ReferenceQueue<K> queue) {
            super(key, queue);
            this.hash = System.identityHashCode(key);
        }

        @Override
        public Object referent() {
            return get();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            Object mine = get();
            return mine != null && other instanceof Key key && key.referent() == mine;
        }
    }

    private record Lookup(Object referent) implements Key {
        @Override
        public int hashCode() {
            return System.identityHashCode(referent);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.referent() == referent;
        }
    }
}
