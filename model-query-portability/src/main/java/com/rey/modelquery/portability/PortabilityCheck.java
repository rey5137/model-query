package com.rey.modelquery.portability;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.portability.internal.CallResult;
import com.rey.modelquery.portability.internal.DryCall;
import com.rey.modelquery.portability.internal.DryRun;
import com.rey.modelquery.portability.internal.OfflineUnit;
import com.rey.modelquery.portability.internal.Sweep;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The portability check: each call, and each definition a sweep finds, run by the real executor on every target's
 * factory booted offline (R-INS-10), and reported one line per call and target. A call is portable on a target only
 * if it reached a statement with no refusal; a refusal before any statement reports the target unserved, and a
 * refusal after a statement, a call that reached none, or any other throwable fails the check (D-133).
 * {@code REACHED} certifies the refusals before the first statement and that statement's rendering only; a sweep marks
 * a definition whose later statements could still be refused "first statement only" (D-138).
 *
 * <p>Collects calls, runs them all in {@link #assertPortable()}; not thread-safe. Add the module in test scope.
 *
 * @implSpec R-INS-11, R-INS-12
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

    /** Adds the application's own persistence properties, beneath the dry run's overrides (a naming strategy). */
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
     * Runs every call and swept definition on every target, and throws an {@code AssertionError} with one line per
     * call and target, then one per skipped definition with its reason, unless each call reached a statement with no
     * refusal. A check with no target, a swept holder with no definition and a bad {@code skip} fail it too, before
     * any call runs. A passing report is logged at {@code INFO}.
     *
     * @throws AssertionError naming every result, when any is not {@code REACHED}
     * @throws ModelQueryConfigurationException {@code MQ4013} when a target's factory cannot boot offline
     */
    public void assertPortable() {
        List<String> setup = new ArrayList<>(problems);
        List<DryCall> all = new ArrayList<>(calls);
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
        if (!setup.isEmpty()) {
            throw new AssertionError("Portability check set up wrongly, nothing ran:\n" + String.join("\n", setup));
        }
        List<CallResult> results = DryRun.run(unit(), chosen, config, all);
        List<String> lines = new ArrayList<>(results.stream().map(CallResult::line).toList());
        lines.addAll(skips);
        long failed = results.stream().filter(result -> !result.passes()).count();
        if (failed > 0) {
            throw new AssertionError("Not portable: " + failed + " of " + results.size() + " results failed\n"
                    + String.join("\n", lines));
        }
        LOG.log(System.Logger.Level.INFO, "Portable: {0} results\n{1}", results.size(), String.join("\n", lines));
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
