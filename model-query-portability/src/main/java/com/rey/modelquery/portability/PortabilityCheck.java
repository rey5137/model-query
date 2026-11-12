package com.rey.modelquery.portability;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.portability.internal.CallResult;
import com.rey.modelquery.portability.internal.DryCall;
import com.rey.modelquery.portability.internal.DryRun;
import com.rey.modelquery.portability.internal.OfflineFactory;
import com.rey.modelquery.portability.internal.OfflineUnit;
import com.rey.modelquery.portability.internal.Sweep;
import jakarta.persistence.metamodel.Type;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The portability check: each call, and each definition a sweep finds, run by the real executor on every target's
 * factory booted offline (R-INS-10), and reported one line per call and target. A call is portable on a target only
 * if it reached a statement with no refusal; a refusal before any statement reports the target unserved, and a
 * refusal after a statement, a call that reached none, or any other throwable fails the check (D-133).
 * {@code REACHED} certifies the refusals before the first statement and that statement's rendering only; a sweep marks
 * a definition whose later statements could still be refused "first statement only" (D-138). Calls a
 * {@link CallRecorder} recorded {@linkplain #replay(Collection) replay} as calls (D-223).
 *
 * <p>Collects calls, runs them all in {@link #assertPortable()}; not thread-safe. Add the module in test scope.
 *
 * @implSpec R-INS-11, R-INS-12, R-INS-15
 */
@Incubating
public final class PortabilityCheck {

    private static final System.Logger LOG = System.getLogger(PortabilityCheck.class.getName());

    private final String unitName;
    private final List<Class<?>> classes;
    private final ModelQueryConfig config;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> mappingFiles = new ArrayList<>();
    private List<Target> targets;
    private final List<DryCall> calls = new ArrayList<>();
    private final Set<Class<?>> swept = new LinkedHashSet<>();
    private final Map<Field, String> skipped = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<DryCall, RecordedCall> replayed = new IdentityHashMap<>();
    private final Map<Class<?>, String> leftOut = new LinkedHashMap<>();
    // One tally per recorder, the latest snapshot's: two snapshots of one recording count it once.
    private final Map<CallRecorder, CallRecorder.Tally> tallies = new LinkedHashMap<>();

    private PortabilityCheck(String unitName, List<Class<?>> classes, ModelQueryConfig config) {
        this.unitName = unitName;
        this.classes = classes;
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * A check of the persistence unit {@code unit} of {@code persistence.xml} under {@code config}. An explicit
     * {@code config.vendor(...)} is left out, so each target's vendor is detected; the targets default to
     * {@code config.targetVendors()}.
     */
    public static PortabilityCheck of(String unit, ModelQueryConfig config) {
        return new PortabilityCheck(Objects.requireNonNull(unit, "unit"), null, config);
    }

    /**
     * A check of a unit of the entity {@code classes}, with the {@linkplain #properties(Map) properties} and
     * {@linkplain #mappingFiles(String...) mapping files} a class list does not carry, under {@code config}.
     */
    public static PortabilityCheck of(List<Class<?>> classes, ModelQueryConfig config) {
        return new PortabilityCheck(null, List.copyOf(Objects.requireNonNull(classes, "classes")), config);
    }

    /**
     * Adds the application's own persistence properties, beneath the dry run's overrides (a naming strategy).
     * Give values as strings, class names, numbers or booleans: the booted factory is cached by these properties, and
     * an instance value without value equality boots a fresh factory per check.
     */
    public PortabilityCheck properties(Map<String, ?> properties) {
        this.properties.putAll(Objects.requireNonNull(properties, "properties"));
        return this;
    }

    /** Adds mapping files ({@code orm.xml}) to a class list; a named unit lists its own, so it takes none. */
    public PortabilityCheck mappingFiles(String... mappingFiles) {
        for (String file : Objects.requireNonNull(mappingFiles, "mappingFiles")) {
            this.mappingFiles.add(Objects.requireNonNull(file, "mappingFiles"));
        }
        if (unitName != null) {
            problems.add("mapping files " + this.mappingFiles + " are for a class list; unit " + unitName
                    + " lists its own");
        }
        return this;
    }

    /**
     * The targets each call runs on, in place of {@code config.targetVendors()}.
     *
     * @throws ModelQueryConfigurationException {@code MQ4012} for a target whose vendor is outside the declared
     *     {@code config.targetVendors()}: production would refuse that factory
     */
    public PortabilityCheck targets(Target... targets) {
        List<Target> chosen = List.of(Objects.requireNonNull(targets, "targets"));
        config.targetVendors().ifPresent(declared -> {
            for (Target target : chosen) {
                if (!declared.contains(target.vendor())) {
                    throw new ModelQueryConfigurationException(MqCode.MQ4012, "Portability target " + target
                            + " is not one of the declared target vendors " + declared + ", so no factory on "
                            + target.vendor() + " would be served; check a declared vendor, or declare it");
                }
            }
        });
        this.targets = chosen;
        return this;
    }

    /** Runs {@code body} on an executor of {@code root} in a stub transaction, labelled by its position. */
    public <E> PortabilityCheck call(Class<E> root, Function<ModelQueryExecutor<E>, ?> body) {
        return call(defaultLabel(root), root, body);
    }

    /** Runs {@code body} on an executor of {@code root} in a stub transaction, labelled {@code label}. */
    public <E> PortabilityCheck call(String label, Class<E> root, Function<ModelQueryExecutor<E>, ?> body) {
        calls.add(DryCall.of(Objects.requireNonNull(label, "label"), root, body, false));
        return this;
    }

    /**
     * Runs {@code body} on an executor of {@code root} with no transaction, as the application runs it (a
     * {@code commitEachChunk()} write, or a call R-QRY-17's transaction check refuses), labelled by its position.
     */
    public <E> PortabilityCheck callOutsideTransaction(Class<E> root, Function<ModelQueryExecutor<E>, ?> body) {
        return callOutsideTransaction(defaultLabel(root), root, body);
    }

    /** As {@link #callOutsideTransaction(Class, Function)}, labelled {@code label}. */
    public <E> PortabilityCheck callOutsideTransaction(String label, Class<E> root,
            Function<ModelQueryExecutor<E>, ?> body) {
        calls.add(DryCall.of(Objects.requireNonNull(label, "label"), root, body, true));
        return this;
    }

    private String defaultLabel(Class<?> root) {
        return "call " + (calls.size() + 1) + " (" + Objects.requireNonNull(root, "root").getSimpleName() + ")";
    }

    /**
     * Runs every {@code static final} definition of each holder with its default operation: {@code list} for a
     * {@code ModelQuery}, the plain write for a {@code ModelUpdate} or {@code ModelDelete}, the insert for a
     * {@code ModelInsert} or {@code ValuesInsert}, {@code persist} for a {@code ModelPersist}. A holder with no
     * definition fails the check. Holders are explicit: there is no package scan.
     */
    public PortabilityCheck sweep(Class<?>... holders) {
        for (Class<?> holder : Objects.requireNonNull(holders, "holders")) {
            swept.add(Objects.requireNonNull(holder, "holders"));
        }
        return this;
    }

    /**
     * Leaves the definition in {@code holder}'s field {@code field} out of the sweep, for {@code reason}, which is
     * required. A field that is not a definition of a swept holder fails the check.
     */
    public PortabilityCheck skip(Class<?> holder, String field, String reason) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(reason, "reason");
        String name = holder.getSimpleName() + "." + field;
        if (reason.isBlank()) {
            problems.add("skip of " + name + " gives no reason");
        }
        Field declared;
        try {
            declared = holder.getDeclaredField(field);
        } catch (NoSuchFieldException e) {
            problems.add("skip of " + name + ": " + holder.getName() + " has no field " + field);
            return this;
        }
        try {
            if (Sweep.isDefinitionField(declared)) {
                skipped.put(declared, reason);
            } else {
                problems.add("skip of " + name + ": not a static final definition");
            }
        } catch (IllegalStateException e) {
            problems.add("skip of " + name + ": " + e.getMessage());
        }
        return this;
    }

    /**
     * Adds each recorded call as a call on every target: the recorded method with the recorded arguments, each function
     * marker replaced by the harness's own function, in or outside a transaction as recorded, labelled
     * {@code callSite method} and {@code xN} when made {@code N > 1} times. A recording's calls past its bound and its
     * failed recordings are each a failing report line; a root outside the unit fails the check unless
     * {@linkplain #leaveOut(Class, String) left out}, and so does replaying no call.
     */
    public PortabilityCheck replay(Collection<RecordedCall> calls) {
        Objects.requireNonNull(calls, "calls");
        if (calls instanceof CallRecorder.Snapshot snapshot) {
            addTally(snapshot.tally());
        }
        if (calls.isEmpty()) {
            problems.add("replay of no recorded calls: record through CallRecorder.wrap(...) before calls()");
        }
        for (RecordedCall call : calls) {
            Objects.requireNonNull(call, "calls");
            addTally(call.tally());
            DryCall dry = replayCall(call);
            this.calls.add(dry);
            replayed.put(dry, call);
        }
        return this;
    }

    /**
     * Leaves the recorded calls on {@code root}, an entity of another unit, out of the replay, for {@code reason},
     * which is required. A root this check's unit manages fails the check: it is checked here too.
     */
    public PortabilityCheck leaveOut(Class<?> root, String reason) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            problems.add("leaveOut of " + root.getSimpleName() + " gives no reason");
        }
        leftOut.put(root, reason);
        return this;
    }

    @SuppressWarnings("unchecked")
    private static DryCall replayCall(RecordedCall call) {
        return DryCall.of(call.label(), (Class<Object>) call.root(), call::replay, call.outsideTransaction());
    }

    private void addTally(CallRecorder.Tally tally) {
        tallies.merge(tally.recorder(), tally, (seen, next) -> next.sequence() > seen.sequence() ? next : seen);
    }

    /**
     * Runs every call and swept definition on every target, and throws an {@code AssertionError} with one line per
     * call and target (identical replayed lines as one), then one per recording count, per skipped definition and per
     * left-out root with its reason, unless each call reached a statement with no refusal and no recording dropped or
     * failed a call. A check with no target, a swept holder with no definition, a bad {@code skip} or
     * {@code leaveOut}, an empty replay and a replayed root outside the unit fail it too, before any call runs, each
     * listed with the recording counts. A recorder's counts are its latest replayed snapshot's, once. A passing report
     * is logged at {@code INFO}.
     *
     * @throws AssertionError naming every result, when any is not {@code REACHED} or a recording count is not zero
     * @throws ModelQueryConfigurationException {@code MQ4013} when a target's factory cannot boot offline
     */
    public void assertPortable() {
        List<String> setup = new ArrayList<>(problems);
        List<DryCall> all = new ArrayList<>(calls);
        all.removeIf(call -> replayed.containsKey(call) && leftOut.containsKey(replayed.get(call).root()));
        List<String> skips = new ArrayList<>();
        all.addAll(sweptCalls(setup, skips));
        if (classes != null && classes.isEmpty()) {
            setup.add("no entity classes in the unit");
        }
        // The declared vendors at the line's minimum dialect version, unless targets(...) chose.
        List<Target> chosen = targets != null ? targets
                : config.targetVendors().map(vendors -> vendors.stream().map(Target::of).toList()).orElse(List.of());
        if (chosen.isEmpty()) {
            setup.add("no targets: pass targets(...) or declare config.targetVendors(...)");
        }
        if (all.isEmpty()) {
            setup.add("no calls: add call(...) or sweep(...)");
        }
        if (!chosen.isEmpty() && !(classes != null && classes.isEmpty())) {
            setup.addAll(rootProblems(chosen.get(0), all));
        }
        List<String> recording = tallies.values().stream().flatMap(tally -> tally.lines().stream()).toList();
        if (!setup.isEmpty()) {
            // A recording's counts still show: an empty replay may be empty because every call was dropped or failed.
            setup.addAll(recording);
            throw new AssertionError("Portability check set up wrongly, nothing ran:\n" + String.join("\n", setup));
        }
        // A COUNT page contributes separate count and content results (D-222).
        List<CallResult> results = collapse(all, DryRun.runEach(unit(), chosen, config, all));
        List<String> lines = new ArrayList<>(results.stream().map(CallResult::line).toList());
        lines.addAll(recording);
        lines.addAll(skips);
        leftOut.forEach((root, reason) -> lines.add(root.getSimpleName() + " left out: " + reason));
        int total = results.size() + recording.size();
        long failed = results.stream().filter(result -> !result.passes()).count() + recording.size();
        if (failed > 0) {
            throw new AssertionError("Not portable: " + failed + " of " + total + " results failed\n"
                    + String.join("\n", lines));
        }
        LOG.log(System.Logger.Level.INFO, "Portable: {0} results\n{1}", total, String.join("\n", lines));
    }

    /**
     * A setup problem per replayed root the unit does not manage and per left-out root it does, read from the
     * metamodel of {@code target}'s factory; boots nothing when no call is replayed or left out.
     */
    private List<String> rootProblems(Target target, List<DryCall> all) {
        Set<Class<?>> roots = all.stream().filter(replayed::containsKey).map(call -> replayed.get(call).root())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (roots.isEmpty() && leftOut.isEmpty()) {
            return List.of();
        }
        Set<Class<?>> managed = OfflineFactory.boot(unit(), target).getMetamodel().getEntities().stream()
                .map(Type::getJavaType).collect(Collectors.toSet());
        List<String> found = new ArrayList<>();
        roots.stream().filter(root -> !managed.contains(root)).forEach(root -> found.add("replayed root "
                + root.getName() + " is not an entity of the unit: check its calls with its own unit, or leaveOut("
                + root.getSimpleName() + ".class, reason)"));
        leftOut.keySet().stream().filter(managed::contains).forEach(root -> found.add("leaveOut of "
                + root.getSimpleName() + ": the unit manages it, so its calls are checked here too"));
        return found;
    }

    /**
     * {@code results} with identical replayed lines (call site, method, target, outcome, SQL) collapsed into the first,
     * labelled with the calls' summed times; every other result as it is.
     */
    private List<CallResult> collapse(List<DryCall> all, List<List<CallResult>> results) {
        List<CallResult> collapsed = new ArrayList<>();
        Map<List<Object>, Integer> slots = new HashMap<>();
        Map<Integer, Integer> times = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            RecordedCall call = replayed.get(all.get(i));
            for (CallResult result : results.get(i)) {
                if (call == null) {
                    collapsed.add(result);
                    continue;
                }
                List<Object> line = List.of(call.site(), result.target(), result.outcome(),
                        result.firstStatementOnly(), result.detail(), result.statement());
                Integer slot = slots.putIfAbsent(line, collapsed.size());
                if (slot == null) {
                    times.put(collapsed.size(), call.times());
                    collapsed.add(new CallResult(call.site(), result.target(), result.outcome(),
                            result.firstStatementOnly(), result.detail(), result.statement()));
                } else {
                    times.merge(slot, call.times(), Integer::sum);
                }
            }
        }
        times.forEach((slot, sum) -> {
            CallResult result = collapsed.get(slot);
            collapsed.set(slot, new CallResult(RecordedCall.label(result.label(), sum), result.target(),
                    result.outcome(), result.firstStatementOnly(), result.detail(), result.statement()));
        });
        return collapsed;
    }

    /** The swept calls; adds a setup problem per bad sweep or skip, and a report line per skipped definition. */
    private List<DryCall> sweptCalls(List<String> setup, List<String> skips) {
        List<DryCall> sweptCalls = new ArrayList<>();
        Set<Field> used = new LinkedHashSet<>();
        for (Class<?> holder : swept) {
            List<Field> fields;
            try {
                fields = Sweep.definitionFields(holder);
            } catch (IllegalStateException e) {
                setup.add("sweep of " + holder.getName() + ": " + e.getMessage());
                continue;
            }
            if (fields.isEmpty()) {
                setup.add("sweep of " + holder.getName() + " finds no static final definition");
            }
            for (Field field : fields) {
                if (skipped.containsKey(field)) {
                    used.add(field);
                    skips.add(field.getDeclaringClass().getSimpleName() + "." + field.getName() + " skipped: "
                            + skipped.get(field));
                } else {
                    sweptCalls.add(Sweep.call(field));
                }
            }
        }
        skipped.keySet().stream().filter(field -> !used.contains(field)).forEach(field -> setup.add("skip of "
                + field.getDeclaringClass().getSimpleName() + "." + field.getName() + ": its holder is not swept"));
        return sweptCalls;
    }

    private OfflineUnit unit() {
        return unitName != null ? OfflineUnit.named(unitName, properties)
                : OfflineUnit.ofClasses(classes, mappingFiles, properties);
    }
}
