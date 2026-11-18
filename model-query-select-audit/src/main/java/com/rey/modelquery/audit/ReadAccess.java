package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectField;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry points called by the injected model read hooks (R-INS-19). Not API: called by agent-injected code only, which
 * is why it is public (D-243).
 */
@Incubating
public final class ReadAccess {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<Object> MAPPING = new ThreadLocal<>();
    private static final WeakIdentityMap<Object, Set<Object>> ROW_READS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<Object, Set<String>> ROW_ALIASES = new WeakIdentityMap<>();
    private static final ThreadLocal<String[]> ALIAS_PROBE = new ThreadLocal<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private ReadAccess() {}

    /** Records an accessor read on a tagged model; {@code property} is fixed when the class is transformed. */
    @Incubating
    public static void accessor(Object model, String property, String method, Class<?> owner) {
        Tag tag = AuditObserver.tagOf(model);
        if (tag != null) {
            read(tag, model, !tag.isMapped(property) && tag.isMapped(method) ? method : property, owner.getName());
        }
    }

    /** Records a direct field read on a tagged model. */
    @Incubating
    public static void field(Object model, String property, String owner) {
        Tag tag = AuditObserver.tagOf(model);
        if (tag != null) {
            read(tag, model, property, owner);
        }
    }

    private static void read(Tag tag, Object model, String property, String owner) {
        if (DEPTH.get()[0] == 0) {
            if (tag.isMapped(property)) {
                if (tag.isSelected(property)) {
                    tag.read(property);
                    return;
                }
                ReadSite.Site site = ReadSite.capture(model.getClass());
                if (site.ignored()) {
                    return;
                }
                String name = model.getClass().getSimpleName() + "." + property;
                tag.unselected(name, property, site);
                String label = Thread.currentThread().getId() != tag.handoffThread()
                        ? " (may not have failed a test)" : "";
                String message = name + " was not selected\n  read at   " + site.location() + label
                        + "\n  model of  " + tag.callSite() + " " + tag.method() + ", select " + tag.selectLabel()
                        + "\n  add " + tag.constant(property) + " to the select set, or ignore this reader "
                        + "(modelquery.selectAudit.ignore)";
                if (site.serializer()) {
                    System.getLogger(ReadAccess.class.getName()).log(System.Logger.Level.WARNING, message);
                } else if (!"none".equals(System.getProperty("modelquery.selectAudit.failOn", "unselected"))) {
                    throw new UnselectedColumnRead(message);
                }
            } else if (!model.getClass().getName().equals(owner)) {
                tag.inheritedRead(model.getClass(), property, owner);
            }
        }
    }

    /** Suppresses reads inside a model's own object method. */
    @Incubating
    public static void enterObjectMethod() {
        DEPTH.get()[0]++;
    }

    /** Restores read tracking after a model's own object method. */
    @Incubating
    public static void exitObjectMethod() {
        int[] depth = DEPTH.get();
        if (--depth[0] == 0) {
            DEPTH.remove();
        }
    }

    /** Starts a mapping scope, returning the previous scope for nested mappings. */
    @Incubating
    public static Object enterMapping(Object row) {
        Object previous = MAPPING.get();
        MAPPING.set(row);
        return previous;
    }

    /** Restores the mapping scope after mapping, including exceptional exits. */
    @Incubating
    public static void exitMapping(Object previous) {
        if (previous == null) {
            MAPPING.remove();
        } else {
            MAPPING.set(previous);
        }
    }

    /** Records a row read made by mapping callbacks rather than a generated mapper. */
    @Incubating
    public static void rowRead(Object column) {
        Object row = MAPPING.get();
        if (row == null || WALKER.walk(frames -> frames.anyMatch(frame ->
                ModelProperties.hasFieldsMethod(frame.getDeclaringClass())))) {
            return;
        }
        synchronized (ROW_READS) {
            ROW_READS.computeIfAbsent(row, ConcurrentHashMap::newKeySet).add(column);
        }
    }

    /** Records the resolved column alias, including through scoped rows. */
    @Incubating
    public static void rowAlias(String alias) {
        String[] probe = ALIAS_PROBE.get();
        if (probe != null) {
            probe[0] = alias;
            return;
        }
        Object row = MAPPING.get();
        if (row == null || alias == null
                || !WALKER.walk(frames -> frames.anyMatch(frame -> frame.getMethodName().equals("raw")))
                || WALKER.walk(frames -> frames.anyMatch(
                        frame -> ModelProperties.hasFieldsMethod(frame.getDeclaringClass())))) {
            return;
        }
        synchronized (ROW_ALIASES) {
            ROW_ALIASES.computeIfAbsent(row, ConcurrentHashMap::newKeySet).add(alias);
        }
    }

    static boolean wasRowRead(Row row, SelectField<?, ?> field) {
        if (rowReads(row).contains(field)) {
            return true;
        }
        Set<String> aliases = ROW_ALIASES.get(row);
        if (aliases == null || aliases.isEmpty()) {
            return false;
        }
        String[] probe = new String[1];
        ALIAS_PROBE.set(probe);
        try {
            row.isSelected(field);
        } finally {
            ALIAS_PROBE.remove();
        }
        return probe[0] != null && aliases.contains(probe[0]);
    }

    static Set<Object> rowReads(Object row) {
        Set<Object> reads = ROW_READS.get(row);
        return reads == null ? Set.of() : reads;
    }
}
