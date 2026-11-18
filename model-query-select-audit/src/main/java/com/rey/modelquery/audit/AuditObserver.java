package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.jpa.vendor.HandOffObserver;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Observes models handed off by the JPA executor and tags them with their selected columns (RFC 0026, R-INS-18).
 */
@Incubating
public final class AuditObserver implements HandOffObserver {

    private static final Logger LOG = System.getLogger(AuditObserver.class.getName());
    private static final AtomicBoolean WARNED_MISSING_AGENT = new AtomicBoolean();
    private static final WeakIdentityMap<Object, Tag> TAGS = new WeakIdentityMap<>();
    private static final Set<Class<?>> MISSING_HOOKS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    /** Creates an audit observer. */
    @Incubating
    public AuditObserver() {
        this(CallSiteCapture::capture);
    }

    private final Supplier<CallSiteCapture.Site> capture;

    AuditObserver(Supplier<CallSiteCapture.Site> capture) {
        this.capture = capture;
    }

    /** Looks up the audit tag for {@code model}, or {@code null} if untagged. */
    static Tag tagOf(Object model) {
        return model != null ? TAGS.get(model) : null;
    }

    static void clearMissingHooks() {
        MISSING_HOOKS.clear();
    }

    private static final ThreadLocal<Call> CALL = new ThreadLocal<>();

    private record TagKey(String method, String prefix, Class<?> model, BitSet selected) {}

    private static final class Call {
        private final CallSiteCapture.Site site;
        private final IdentityHashMap<ModelQuery<?, ?, ?>, Map<TagKey, Tag>> tags = new IdentityHashMap<>();

        private Call(CallSiteCapture.Site site) {
            this.site = site;
        }
    }

    private static void restore(Call previous) {
        if (previous == null) {
            CALL.remove();
        } else {
            CALL.set(previous);
        }
    }

    /** Active only with the agent attached; without it, one warning and the executor keeps no rows (D-242). */
    @Override
    @Incubating
    public boolean active() {
        if (AuditAgent.isInstalled()) {
            return true;
        }
        warnMissingAgent();
        return false;
    }

    private static void warnMissingAgent() {
        if (WARNED_MISSING_AGENT.compareAndSet(false, true)) {
            LOG.log(Level.WARNING, "model-query-select-audit is on the classpath without its agent; audit is disabled");
        }
    }

    @Override
    @Incubating
    public Scope openCall() {
        if (!AuditAgent.isInstalled()) {
            return Scope.NONE;
        }
        Call previous = CALL.get();
        Call opened = previous == null ? new Call(capture.get()) : previous;
        CALL.set(opened);
        return new Scope() {
            @Override
            @Incubating
            public Scope enter() {
                Call workerPrevious = CALL.get();
                CALL.set(opened);
                return () -> restore(workerPrevious);
            }

            @Override
            @Incubating
            public void close() {
                restore(previous);
                if (previous == null) {
                    synchronized (opened) {
                        opened.tags.clear();
                    }
                }
            }
        };
    }

    @Override
    @Incubating
    public void handedOff(ModelQuery<?, ?, ?> query, Optional<ChildLoad<?, ?>> child, List<?> models,
            List<Row> rows) {
        if (!AuditAgent.isInstalled()) {
            warnMissingAgent();
            return;
        }
        String unapplied = ReadTransformer.unappliedSeams();
        if (unapplied != null) {
            throw new IllegalStateException("select-audit engine seams loaded without their hooks (D-241): "
                    + unapplied + "; a core or jpa refactor renamed one, or the agent attached after the class loaded");
        }
        if (models.isEmpty()) {
            return;
        }
        Call call = CALL.get();
        if (call == null) {
            call = new Call(capture.get());
        }
        if (call.site == null) {
            return;
        }
        ModelProperties props = ModelProperties.of(query, models.get(0).getClass());
        if (props == null) {
            return;
        }
        String method = child.map(load -> "child " + load.field().name() + " select "
                + props.describeSelect(query.select())).orElse(call.site.method());
        synchronized (call) {
            Map<TagKey, Tag> tags = call.tags.computeIfAbsent(query, ignored -> new HashMap<>());
            for (int i = 0; i < models.size(); i++) {
                tagModel(models.get(i), rows.get(i), query, props, tags, call.site.callSite(), method,
                        call.site.test());
            }
        }
    }

    private void tagModel(Object model, Row row, ModelQuery<?, ?, ?> query, ModelProperties props,
            Map<TagKey, Tag> tags, String callSite, String method, String test) {
        TagKey key = new TagKey(method, props.prefix(), model.getClass(), props.selectionBitSet(query, row));
        Tag tag = tags.computeIfAbsent(key, ignored -> {
            Tag created = new Tag(callSite, method, query.select(), props.describeSelect(query.select()),
                    model.getClass().getName(), key.selected(), props.propertyBits(), props.mapped(),
                    props.constants(), props.requestedProperties(query));
            ReadEvents.recordCall(created, test);
            return created;
        });
        props.recordMappingReads(tag, row);
        if (!ReadTransformer.hooked(model.getClass())) {
            ReadEvents.recordMissingHook(model.getClass().getName(), callSite, method);
            if (MISSING_HOOKS.add(model.getClass())) {
                LOG.log(Level.WARNING, "MODELS NOT OBSERVED (hook missing): " + model.getClass().getName()
                        + " model of " + callSite + " " + method);
            }
        }
        for (String property : props.joins().keySet()) {
            if (!tag.isSelected(property)) {
                continue;
            }
            Object value;
            ReadAccess.enterObjectMethod();
            try {
                value = ModelProperties.read(model, property);
            } finally {
                ReadAccess.exitObjectMethod();
            }
            if (value instanceof Optional<?> optional && optional.isPresent()) {
                Object nested = optional.get();
                tagModel(nested, row, query, props.nested(property, nested.getClass()), tags, callSite, method, test);
            }
        }
        TAGS.put(model, tag);
    }
}
