package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.processor.ModelBindings.Binding;
import com.rey.modelquery.processor.ModelBindings.Origin;
import com.rey.modelquery.processor.ModelDefinition.Kind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

/**
 * Generates a QModel class for every type annotated with {@code @QueryModel}, {@code @UpdateModel} or
 * {@code @InsertModel}: each model is read, validated and, when it has no error, written to one file of its own,
 * beside its change set when it has one. A type carrying two of them is reported and generates nothing. A model
 * bound by {@code @BindQueryModel}, {@code @BindUpdateModel} or {@code @BindInsertModel} is read from the classpath or
 * the compilation and generated in the binding's package, its files originating from the binding alone.
 *
 * @implSpec R-GEN-01, R-GEN-05, R-GEN-19, R-GEN-21, R-GEN-23, R-GEN-35, R-PROC-24, R-PROC-28, R-PROC-29, R-PROC-30,
 *     R-DIAG-03, D-107, D-140
 */
@Incubating
@SupportedAnnotationTypes({"com.rey.modelquery.annotations.QueryModel", "com.rey.modelquery.annotations.SourceModel",
        "com.rey.modelquery.annotations.UpdateModel",
        "com.rey.modelquery.annotations.InsertModel", "com.rey.modelquery.annotations.BindQueryModel",
        "com.rey.modelquery.annotations.BindQueryModels", "com.rey.modelquery.annotations.BindUpdateModel",
        "com.rey.modelquery.annotations.BindUpdateModels", "com.rey.modelquery.annotations.BindInsertModel",
        "com.rey.modelquery.annotations.BindInsertModels"})
@SupportedOptions({QueryModelReader.PREFIX_OPTION, QueryModelReader.SUFFIX_OPTION,
        ModelQueryProcessor.JSPECIFY_OPTION, ModelQueryProcessor.AGGREGATING_OPTION})
public final class ModelQueryProcessor extends AbstractProcessor {

    /** The processor option that adds JSpecify nullness annotations to the generated classes (R-PROC-32). */
    static final String JSPECIFY_OPTION = "modelquery.jspecify";

    /**
     * The processor option that registers the processor with Gradle as aggregating rather than isolating, so an
     * incremental compile reads every binding of the compilation, not only those of the recompiled files.
     */
    static final String AGGREGATING_OPTION = "modelquery.aggregating";

    /** Gradle's option naming a dynamic processor isolating. */
    private static final String GRADLE_ISOLATING = "org.gradle.annotation.processing.isolating";

    /** Gradle's option naming a dynamic processor aggregating. */
    private static final String GRADLE_AGGREGATING = "org.gradle.annotation.processing.aggregating";

    private static final String ROOT_UNRESOLVED =
            "root does not name a class, and no annotation processor generated one";

    /** What the first round decided: the JSpecify annotations to write, or none; {@code null} before it. */
    private Nullness nullness;

    /** The models whose {@code root}, or a nested model's, named no class in the round before, to retry in this one. */
    private final List<Deferred> deferred = new ArrayList<>();

    /**
     * The qualified names the annotated models read so far, in this round or an earlier one, generate, each with the
     * model that generates it: a binding generating one of them is {@code MQ3023} (D-141).
     */
    private final Map<String, String> annotatedFiles = new LinkedHashMap<>();

    /** Where the bindings of this round and earlier ones are written, to read them again in a later round. */
    private final Set<Origin> origins = new LinkedHashSet<>();

    /** The bindings whose model's {@code root}, or a nested model's, named no class in the round before (D-107). */
    private final List<DeferredBinding> deferredBindings = new ArrayList<>();

    /**
     * The qualified names the bindings generated in an earlier round generate, each with its binding: a binding of a
     * later round generating one of them is {@code MQ3023} (D-142).
     */
    private final Map<String, String> boundFiles = new LinkedHashMap<>();

    /**
     * The (kind, model) pairs the bindings generated in an earlier round bind, each with the origin it was bound on:
     * a binding of a later round binding one again, in another package, is {@code MQ3023} (R-PROC-28, D-142).
     */
    private final Map<List<Object>, String> boundModels = new LinkedHashMap<>();

    /**
     * The declared options and Gradle's incremental kind for this dynamic processor: aggregating when
     * {@value #AGGREGATING_OPTION} is {@code true}, ignoring case, otherwise isolating, as it is before {@code init}.
     * Gradle asks after {@code init}.
     */
    @Override
    public Set<String> getSupportedOptions() {
        var options = new LinkedHashSet<>(super.getSupportedOptions());
        var aggregating = isInitialized() && Boolean.parseBoolean(processingEnv.getOptions().get(AGGREGATING_OPTION));
        options.add(aggregating ? GRADLE_AGGREGATING : GRADLE_ISOLATING);
        return options;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        var types = processingEnv.getTypeUtils();
        var reader = new QueryModelReader(processingEnv.getOptions(), types);
        var elements = processingEnv.getElementUtils();
        // The bindings of earlier rounds are read again from where they are written; with this round's they are the
        // compilation's, whose query models any model may nest (R-PROC-31).
        List<Element> current = ModelBindings.origins(roundEnv);
        List<Binding> earlier = ModelBindings.on(
                origins.stream().map(origin -> origin.find(elements)).filter(Objects::nonNull).toList());
        current.forEach(origin -> origins.add(Origin.of(origin)));
        List<Binding> fresh = ModelBindings.on(current);
        Map<String, Binding> bound = new HashMap<>();
        Stream.concat(earlier.stream(), fresh.stream())
                .filter(binding -> binding.kind() == Kind.QUERY && ModelBindings.modelType(binding) != null)
                .forEach(binding -> bound.putIfAbsent(
                        ModelBindings.modelType(binding).getQualifiedName().toString(), binding));
        var nestedModels = new NestedModels(reader, bound);
        var metamodel = new EntityMetamodel(types, elements);
        var builtIns = new BuiltInConverters(elements);
        if (nullness == null) {
            nullness = nullness(elements);
        }
        var round = new Round(reader, nestedModels, new ModelValidator(types, metamodel, nestedModels, builtIns),
                new QModelWriter(types, metamodel, nestedModels, builtIns, nullness),
                // A model module without Bean Validation, or without model-query-jpa, compiles and references neither.
                new ChangesWriter(types, metamodel, elements.getTypeElement(ChangesWriter.VALID_CHANGES) != null
                        && elements.getTypeElement(ChangesWriter.CONSTRAINT) != null, nullness),
                metamodel, roundEnv.processingOver());
        // An element is only valid in the round that produced it, so a deferred model is looked up again by name.
        var retried = List.copyOf(deferred);
        deferred.clear();
        for (Deferred model : retried) {
            TypeElement type = elements.getTypeElement(model.name());
            if (type != null) {
                generate(type, model.kind(), round);
                continue;
            }
            // An ambiguous name across JPMS modules resolves to no type element: report it rather than drop the
            // model silently (D-107).
            new Diagnostics(processingEnv.getMessager()).error(DiagnosticCode.MQ3017, model.name()
                    + ": could not be resolved: root does not name a class, and no annotation processor generated one");
        }
        for (Kind kind : Kind.values()) {
            for (Element element : roundEnv.getElementsAnnotatedWith(QueryModelReader.annotation(kind))) {
                var type = (TypeElement) element;
                List<Kind> carried = Arrays.stream(Kind.values())
                        .filter(each -> QueryModelReader.mirror(type, QueryModelReader.annotation(each)) != null)
                        .toList();
                if (carried.size() == 1) {
                    generate(type, kind, round);
                } else if (carried.get(0) == kind) {
                    reportTwoKinds(type, carried);
                }
            }
        }
        var bindings = new ArrayList<Binding>();
        var retriedBindings = List.copyOf(deferredBindings);
        deferredBindings.clear();
        for (DeferredBinding deferredBinding : retriedBindings) {
            earlier.stream().filter(deferredBinding::matches).findFirst().ifPresentOrElse(bindings::add,
                    () -> new Diagnostics(processingEnv.getMessager()).error(DiagnosticCode.MQ3017,
                            deferredBinding.model() + ", bound on " + deferredBinding.origin().name()
                                    + ": could not be resolved: " + ROOT_UNRESOLVED));
        }
        bindings.addAll(fresh);
        bindings(bindings, round);
        return false;
    }

    /**
     * Generates each bound model, unless it is bound twice for one kind or also carries a model annotation, which is
     * {@code MQ3023} on each of its bindings of that kind, or its binding would generate a class of one name with
     * another binding or an annotated model, which is {@code MQ3023} on each of those bindings (R-PROC-28, D-141).
     * {@code bindings} are the round's and those deferred from the round before (D-142).
     */
    private void bindings(List<Binding> bindings, Round round) {
        var diagnostics = new Diagnostics(processingEnv.getMessager());
        var read = new ArrayList<BoundModel>();
        Map<List<Object>, List<Binding>> byModel = bindings.stream().collect(Collectors.groupingBy(
                ModelQueryProcessor::key, LinkedHashMap::new,
                Collectors.toList()));
        for (List<Binding> same : byModel.values()) {
            Binding binding = same.get(0);
            String bind = "@" + ModelBindings.annotation(binding.kind()).getSimpleName();
            TypeElement type = ModelBindings.modelType(binding);
            String name = type == null ? binding.model().toString() : type.getSimpleName().toString();
            List<String> origins = same.stream().map(each -> ModelBindings.name(each.origin())).distinct().toList();
            List<String> carried = type == null ? List.of() : Arrays.stream(Kind.values())
                    .filter(kind -> QueryModelReader.mirror(type, QueryModelReader.annotation(kind)) != null)
                    .map(kind -> "@" + QueryModelReader.annotation(kind).getSimpleName())
                    .toList();
            String earlier = boundModels.get(key(binding));
            if (earlier != null) {
                same.stream().map(Binding::origin).distinct().forEach(origin -> diagnostics.error(origin,
                        DiagnosticCode.MQ3023, name + ": " + bind + " binds it more than once, on " + earlier
                                + " and " + String.join(" and ", origins) + "; keep one"));
            } else if (same.size() > 1) {
                same.stream().map(Binding::origin).distinct().forEach(origin -> diagnostics.error(origin,
                        DiagnosticCode.MQ3023, name + ": " + bind + " binds it more than once, on "
                                + String.join(" and ", origins) + "; keep one"));
            } else if (!carried.isEmpty()) {
                diagnostics.error(binding.origin(), DiagnosticCode.MQ3023, name + ": carries "
                        + String.join(" and ", carried) + " and is bound by " + bind + " on " + origins.get(0)
                        + "; keep one");
            } else if (type == null) {
                diagnostics.error(binding.origin(), DiagnosticCode.MQ3024, name
                        + ": a bound model must be a public class or record");
            } else {
                read.add(new BoundModel(binding, type,
                        round.reader().read(type, binding.kind(), binding.annotation(), binding.origin())));
            }
        }
        Set<BoundModel> clashing = clashing(read, diagnostics);
        read.stream().filter(bound -> !clashing.contains(bound)).forEach(bound -> generate(bound, round));
    }

    /**
     * The bound models whose binding would generate a class of one qualified name with another binding of the round,
     * of this model or another, with a binding generated in an earlier round, or with an annotated model read so far:
     * {@code MQ3023} once on each of those bindings, instead of the {@code Filer}'s refusal, and the annotated model
     * or the earlier binding still generates its own (D-141, D-142). A model whose root names no class yet is checked
     * once it does; a class already on the classpath is left to the {@code Filer}.
     */
    private Set<BoundModel> clashing(List<BoundModel> read, Diagnostics diagnostics) {
        Map<String, List<BoundModel>> byFile = new LinkedHashMap<>();
        for (BoundModel bound : read) {
            files(bound.model()).forEach(file -> byFile.computeIfAbsent(file, key -> new ArrayList<>()).add(bound));
        }
        var clashing = new LinkedHashSet<BoundModel>();
        for (BoundModel bound : read) {
            List<String> clashed = files(bound.model()).stream()
                    .filter(file -> byFile.get(file).size() > 1 || annotatedFiles.containsKey(file)
                            || boundFiles.containsKey(file))
                    .toList();
            if (clashed.isEmpty()) {
                continue;
            }
            clashing.add(bound);
            List<String> generators = new ArrayList<>(read.stream()
                    .filter(other -> clashed.stream().anyMatch(file -> byFile.get(file).contains(other)))
                    .map(other -> "@" + ModelBindings.annotation(other.binding().kind()).getSimpleName()
                            + (other.type().equals(bound.type()) ? "" : " of " + other.type().getQualifiedName())
                            + " on " + ModelBindings.name(other.binding().origin()))
                    .toList());
            Stream.concat(clashed.stream().map(boundFiles::get), clashed.stream().map(annotatedFiles::get))
                    .filter(Objects::nonNull).distinct().forEach(generators::add);
            boolean qModels = clashed.stream().noneMatch(file -> file.endsWith("." + bound.model().changesName()));
            diagnostics.error(bound.binding().origin(), DiagnosticCode.MQ3023, bound.type().getSimpleName() + ": "
                    + String.join(" and ", generators) + " each generate " + String.join(" and ", clashed) + "; "
                    + (qModels ? "give one another prefix or package" : "bind one in another package"));
        }
        return clashing;
    }

    /** What one binding binds: its kind and the model it names. */
    private static List<Object> key(Binding binding) {
        return List.of(binding.kind(), binding.model().toString());
    }

    /** The qualified names of the classes {@code model} generates, none when it could not be read. */
    private static List<String> files(ModelDefinition model) {
        if (model == null) {
            return List.of();
        }
        String qModel = model.packageName() + "." + model.generatedName();
        return model.changes() ? List.of(qModel, model.packageName() + "." + model.changesName()) : List.of(qModel);
    }

    /**
     * Generates one bound model in its binding's package, or defers it to the next round while its {@code root}, or a
     * nested model's, names no class yet; the last round reports it on the binding instead (D-107).
     */
    private void generate(BoundModel bound, Round round) {
        Binding binding = bound.binding();
        TypeElement type = bound.type();
        ModelDefinition model = bound.model();
        var diagnostics = new Diagnostics(processingEnv.getMessager());
        TypeElement unresolved = model == null ? type : round.nestedModels().unresolved(model);
        if (unresolved != null) {
            if (!round.last()) {
                deferredBindings.add(new DeferredBinding(Origin.of(binding.origin()), binding.kind(),
                        binding.model().toString()));
                return;
            }
            diagnostics.error(binding.origin(), DiagnosticCode.MQ3017, type.getSimpleName() + ": "
                    + (unresolved == type ? ROOT_UNRESOLVED : "nests " + unresolved.getSimpleName() + ", whose "
                            + ROOT_UNRESOLVED));
            return;
        }
        String generator = "@" + ModelBindings.annotation(binding.kind()).getSimpleName() + " of "
                + type.getQualifiedName() + " on " + ModelBindings.name(binding.origin());
        files(model).forEach(file -> boundFiles.put(file, generator));
        boundModels.put(key(binding), ModelBindings.name(binding.origin()));
        ModelBindings.checkExpressions(binding, type, diagnostics);
        validateAndWrite(model, diagnostics, round);
    }

    /**
     * The nullness annotations the option asks for. A compilation without JSpecify on its classpath gets
     * {@code MQ3027} once, here, and none (R-PROC-32).
     */
    private Nullness nullness(Elements elements) {
        if (!Boolean.parseBoolean(processingEnv.getOptions().get(JSPECIFY_OPTION))) {
            return Nullness.OFF;
        }
        if (elements.getTypeElement(Nullness.NULL_MARKED) == null) {
            new Diagnostics(processingEnv.getMessager()).error(DiagnosticCode.MQ3027, JSPECIFY_OPTION
                    + " is on, but " + Nullness.NULL_MARKED + " is not on the classpath: add org.jspecify:jspecify");
            return Nullness.OFF;
        }
        return new Nullness(true);
    }

    /** {@code MQ3503} for a type carrying several model annotations, each of which would generate its QModel. */
    private void reportTwoKinds(TypeElement type, List<Kind> carried) {
        List<String> names = carried.stream()
                .map(each -> "@" + QueryModelReader.annotation(each).getSimpleName())
                .toList();
        String annotations = String.join(", ", names.subList(0, names.size() - 1)) + " and "
                + names.get(names.size() - 1);
        new Diagnostics(processingEnv.getMessager()).error(type, DiagnosticCode.MQ3503, type.getSimpleName() + ": "
                + annotations + " each generate a QModel class for it; keep one");
    }

    /**
     * Generates one model, or defers it to the next round while its {@code root}, or a nested model's, names no class
     * yet: another processor may generate that class in this round. The last round reports it instead (D-107).
     */
    private void generate(TypeElement type, Kind kind, Round round) {
        ModelDefinition model = round.reader().read(type, kind);
        String generator = "@" + QueryModelReader.annotation(kind).getSimpleName() + " " + type.getQualifiedName();
        files(model).forEach(file -> annotatedFiles.put(file, generator));
        TypeElement unresolved = model == null ? type : round.nestedModels().unresolved(model);
        if (unresolved != null) {
            if (!round.last()) {
                deferred.add(new Deferred(type.getQualifiedName().toString(), kind));
                return;
            }
            // An unresolved class reads as an error value, which keeps no name; javac reports the name on its own.
            new Diagnostics(processingEnv.getMessager()).error(type, DiagnosticCode.MQ3017, type.getSimpleName()
                    + ": " + (unresolved == type ? ROOT_UNRESOLVED
                            : "nests " + unresolved.getSimpleName() + ", whose " + ROOT_UNRESOLVED));
            return;
        }
        validateAndWrite(model, new Diagnostics(processingEnv.getMessager()), round);
    }

    /**
     * Validates {@code model}, then for a bound model checks what its generated classes reach (R-GEN-35), and, when
     * nothing was reported on it, writes its QModel and change set.
     */
    private void validateAndWrite(ModelDefinition model, Diagnostics diagnostics, Round round) {
        round.validator().validate(model, diagnostics);
        if (!diagnostics.hasErrors() && model.origin() != model.type()) {
            ModelBindings.checkAccess(model, model.origin(), processingEnv.getElementUtils(), round.metamodel(),
                    diagnostics);
        }
        if (diagnostics.hasErrors()) {
            return;
        }
        String file = model.generatedName();
        try {
            round.writer().write(model).writeTo(processingEnv.getFiler());
            if (model.changes()) {
                file = model.changesName();
                round.changesWriter().write(model).writeTo(processingEnv.getFiler());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    model.name() + ": could not write " + file + ": " + e.getMessage(), model.type());
        }
    }

    /** A bound model as its binding reads it, {@code model} {@code null} when its root names no class. */
    private record BoundModel(Binding binding, TypeElement type, ModelDefinition model) {}

    /** A model deferred to the next round, by qualified name, and the annotation it carries (D-107). */
    private record Deferred(String name, Kind kind) {}

    /** A binding deferred to the next round: where it is written, its kind and the model it names (D-107). */
    private record DeferredBinding(Origin origin, Kind kind, String model) {

        boolean matches(Binding binding) {
            return Origin.of(binding.origin()).equals(origin) && binding.kind() == kind
                    && binding.model().toString().equals(model);
        }
    }

    /** What one round reads, checks and writes models with. */
    private record Round(
            QueryModelReader reader, NestedModels nestedModels, ModelValidator validator, QModelWriter writer,
            ChangesWriter changesWriter, EntityMetamodel metamodel, boolean last) {}
}
