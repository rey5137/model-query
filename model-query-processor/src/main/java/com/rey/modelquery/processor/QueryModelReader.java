package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.annotations.Depth;
import com.rey.modelquery.annotations.Discriminator;
import com.rey.modelquery.annotations.ExcludeFromDefaults;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.FilterColumns;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.InsertModel;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.JoinEntities;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.JoinSource;
import com.rey.modelquery.annotations.JoinSources;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Selected;
import com.rey.modelquery.annotations.SourceColumn;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Subtype;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.annotations.UpdateModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.processor.ModelDefinition.AggregateDefinition;
import com.rey.modelquery.processor.ModelDefinition.JoinOn;
import com.rey.modelquery.processor.ModelDefinition.JoinSourceDefinition;
import com.rey.modelquery.processor.ModelDefinition.SourceColumnDefinition;
import com.rey.modelquery.processor.ModelDefinition.ChildDefinition;
import com.rey.modelquery.processor.ModelDefinition.EntityJoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.EntityJoinOn;
import com.rey.modelquery.processor.ModelDefinition.EntityJoinWhen;
import com.rey.modelquery.processor.ModelDefinition.FilterColumnDefinition;
import com.rey.modelquery.processor.ModelDefinition.JoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.Kind;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import com.rey.modelquery.processor.ModelDefinition.WindowDefinition;
import com.rey.modelquery.processor.ModelDefinition.WindowOrder;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * Reads a {@code @QueryModel}, {@code @UpdateModel} or {@code @InsertModel} type into a {@link ModelDefinition}. It
 * checks nothing: {@link ModelValidator} does.
 */
final class QueryModelReader {

    /** The processor option that sets {@code @QueryModel(prefix)} for a whole compilation (R-PROC-04). */
    static final String PREFIX_OPTION = "modelquery.prefix";

    /** The processor option that sets {@code @QueryModel(suffix)} for a whole compilation (R-PROC-04). */
    static final String SUFFIX_OPTION = "modelquery.suffix";

    private static final String DEFAULT_PREFIX = "Q";
    private static final String ROOT = "root";
    private static final String PREFIX = "prefix";
    private static final String SUFFIX = "suffix";
    private static final String GENERATE_SELECT_SETS = "generateSelectSets";
    private static final String SINGLE_GROUP = "singleGroup";
    private static final String GENERATE_CHANGES = "generateChanges";
    private static final String GENERATE_INSERTS = "generateInserts";
    private static final String CONVERTER = "converter";
    private static final String TREAT = "treat";
    private static final String OPTIONAL = "java.util.Optional";
    private static final String LIST = "java.util.List";

    private final Map<String, String> options;

    /** What a {@code @Computed} that names no definition reads as, for the validator to report (MQ3025). */
    private final TypeMirror none;

    QueryModelReader(Map<String, String> options, Types types) {
        this.options = options;
        this.none = types.getNoType(TypeKind.VOID);
    }

    /** Reads the {@code @QueryModel} {@code type}, or returns {@code null} as {@link #read(TypeElement, Kind)} does. */
    ModelDefinition read(TypeElement type) {
        return read(type, Kind.QUERY);
    }

    /**
     * Reads {@code type}, which carries the annotation of {@code kind}, or returns {@code null} when its {@code root}
     * does not name a class: one that is not generated yet, which a later round may generate (D-107).
     */
    ModelDefinition read(TypeElement type, Kind kind) {
        return read(type, kind, mirror(type, annotation(kind)), type);
    }

    /**
     * Reads {@code type} with the settings {@code annotation} holds, its own model annotation or the binding on
     * {@code origin} (R-PROC-28), as {@link #read(TypeElement, Kind)} does. A binding's {@code @BindExpression}s supply
     * the definition of a {@code @Computed} that names none, and make an {@code @Aggregate} that names no
     * {@code expression} aggregate theirs (R-PROC-29); {@link ModelBindings#checkExpressions} reports one that matches
     * nothing.
     */
    ModelDefinition read(TypeElement type, Kind kind, AnnotationMirror annotation, Element origin) {
        return read(type, kind, annotation, origin, new HashSet<>());
    }

    private ModelDefinition read(TypeElement type, Kind kind, AnnotationMirror annotation, Element origin,
            Set<TypeElement> roots) {
        roots.add(type);
        if (!(explicit(annotation, ROOT) instanceof DeclaredType rootType) || rootType.getKind() != TypeKind.DECLARED) {
            return null;
        }
        // A name set on the annotation wins over the compilation-wide option, which wins over the default (D-44).
        // An update or insert model has no suffix member, so the option alone sets it (D-69).
        String prefix = explicit(annotation, PREFIX) instanceof String set
                ? set : options.getOrDefault(PREFIX_OPTION, DEFAULT_PREFIX);
        String suffix = explicit(annotation, SUFFIX) instanceof String set
                ? set : options.getOrDefault(SUFFIX_OPTION, "");
        // A source carries every field of its model, so it declares ALL and DEFAULT as a query model does
        // (R-GEN-37). @SourceModel has no generateSelectSets, so the option never turns them off.
        boolean selectSets = (kind == Kind.QUERY || kind == Kind.SOURCE)
                && !Boolean.FALSE.equals(explicit(annotation, GENERATE_SELECT_SETS));
        boolean singleGroup = Boolean.TRUE.equals(explicit(annotation, SINGLE_GROUP));
        boolean generateChanges = Boolean.TRUE.equals(explicit(annotation, GENERATE_CHANGES));
        boolean generateInserts = Boolean.TRUE.equals(explicit(annotation, GENERATE_INSERTS));
        // The first @BindExpression of a field is the one read: the binding check reports a second (MQ3025).
        var expressions = new HashMap<String, TypeMirror>();
        ModelBindings.expressions(annotation).forEach(each -> expressions.putIfAbsent(each.field(), each.value()));

        List<VariableElement> declared = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .filter(field -> !field.getModifiers().contains(Modifier.STATIC))
                .toList();
        // Two @Joins on one attribute are two joins, told apart by their field names (R-PROC-09).
        Map<String, Long> joinsPerAttribute = declared.stream()
                .filter(QueryModelReader::isJoin)
                .collect(Collectors.groupingBy(QueryModelReader::joinAttribute, Collectors.counting()));
        var fields = new ArrayList<ModelField>();
        for (VariableElement field : declared) {
            Column column = field.getAnnotation(Column.class);
            String name = field.getSimpleName().toString();
            // A @Child field is nothing else: the validator reports what it is combined with (MQ3401).
            ChildDefinition child = child(field);
            JoinDefinition join = child == null && isJoin(field)
                    ? join(field, joinsPerAttribute.get(joinAttribute(field)) > 1) : null;
            // A @BindExpression goes to the field's @Computed, if it carries one, and else to its @Aggregate.
            TypeMirror expression = expressions.get(name);
            boolean computed = mirror(field, Computed.class) != null;
            // Read even beside @Transient or @Join, so that the validator reports the pair (MQ3204).
            AggregateDefinition aggregate = child == null ? aggregate(field, computed ? null : expression) : null;
            // A @Computed field is no column: its ExpressionField follows every column (R-GEN-27).
            TypeMirror definition = computed ? computed(field, expression) : null;
            boolean selected = field.getAnnotation(Selected.class) != null;
            WindowDefinition window = child == null ? window(field) : null;
            fields.add(new ModelField(
                    field,
                    definition == null && field.getAnnotation(Transient.class) == null && join == null
                            && aggregate == null && window == null && child == null && !selected,
                    column == null || column.attribute().isEmpty() ? name : column.attribute(),
                    constantName(name),
                    field.getAnnotation(PrimaryKey.class) != null,
                    field.getAnnotation(ExcludeFromDefaults.class) != null,
                    column == null ? null : converter(field),
                    definition,
                    join,
                    aggregate,
                    window,
                    child == null && field.getAnnotation(GroupBy.class) != null,
                    child,
                    selected,
                    discriminator(field),
                    mirror(field, Depth.class) != null,
                    treat(field, Column.class),
                    mirror(field, Subtype.class) != null,
                    sourceColumn(field), column == null ? "" : column.entityJoin()));
        }
        // Both kinds of read may be rooted at a source (R-PROC-39, R-PROC-40). Writes still need an entity.
        TypeElement rootElement = (TypeElement) rootType.asElement();
        // Stop at a repeated root; the root-kind check reports MQ3038 before checking any fields.
        ModelDefinition source = (kind == Kind.QUERY || kind == Kind.SOURCE) && isSourceModel(rootElement)
                && !roots.contains(rootElement)
                ? read(rootElement, Kind.SOURCE, mirror(rootElement, annotation(Kind.SOURCE)), rootElement, roots)
                : null;
        // The shape a source model names is another source model of the compilation, read the same way (R-PROC-38);
        // its own checks report one that is not a source model.
        ModelDefinition shape = kind == Kind.SOURCE && shape(annotation) instanceof DeclaredType shapeType
                && shapeType.getKind() == TypeKind.DECLARED
                ? read((TypeElement) shapeType.asElement(), Kind.SOURCE) : null;
        return new ModelDefinition(
                type, rootElement, prefix + type.getSimpleName() + suffix, selectSets, singleGroup, fields,
                filterColumns(type), kind, generateChanges, generateInserts, origin, source, shape,
                kind == Kind.UPDATE || kind == Kind.INSERT ? List.of() : joinSources(type), entityJoins(type));
    }

    /** What {@code @SourceColumn} says of {@code field}, or {@code null} when it carries none. */
    private static SourceColumnDefinition sourceColumn(VariableElement field) {
        AnnotationMirror mirror = mirror(field, SourceColumn.class);
        return mirror == null ? null : new SourceColumnDefinition(
                explicit(mirror, "join") instanceof String join ? join : "",
                explicit(mirror, "field") instanceof String name ? name : "");
    }

    /** The entity joins declared once or in their repeatable container. */
    private static List<EntityJoinDefinition> entityJoins(TypeElement type) {
        var mirrors = new ArrayList<AnnotationMirror>();
        AnnotationMirror single = mirror(type, JoinEntity.class);
        if (single != null) {
            mirrors.add(single);
        }
        AnnotationMirror container = mirror(type, JoinEntities.class);
        if (container != null && explicit(container, "value") instanceof List<?> repeated) {
            for (Object value : repeated) {
                mirrors.add((AnnotationMirror) ((AnnotationValue) value).getValue());
            }
        }
        var joins = new ArrayList<EntityJoinDefinition>();
        for (AnnotationMirror annotation : mirrors) {
            var on = new ArrayList<EntityJoinOn>();
            if (explicit(annotation, "on") instanceof List<?> pairs) {
                for (Object pair : pairs) {
                    AnnotationMirror each = (AnnotationMirror) ((AnnotationValue) pair).getValue();
                    on.add(new EntityJoinOn(explicit(each, "field") instanceof String field ? field : "",
                            explicit(each, "column") instanceof String column ? column : "",
                            explicit(each, "id") instanceof String id ? id : ""));
                }
            }
            var when = new ArrayList<EntityJoinWhen>();
            if (explicit(annotation, "when") instanceof List<?> terms) {
                for (Object term : terms) {
                    AnnotationMirror each = (AnnotationMirror) ((AnnotationValue) term).getValue();
                    when.add(new EntityJoinWhen(explicit(each, "column") instanceof String column ? column : "",
                            explicit(each, "value") instanceof String value ? value : ""));
                }
            }
            joins.add(new EntityJoinDefinition(explicit(annotation, "name") instanceof String name ? name : "",
                    explicit(annotation, "source") instanceof String source ? source : "", type(annotation, "entity"),
                    explicit(annotation, "type") instanceof Element kind ? kind.getSimpleName().toString()
                            : JoinKind.LEFT.name(), on, when));
        }
        return joins;
    }

    /** The {@code @JoinSource}s of {@code type}, written once or repeated inside a {@code @JoinSources}. */
    private List<JoinSourceDefinition> joinSources(TypeElement type) {
        var mirrors = new ArrayList<AnnotationMirror>();
        AnnotationMirror single = mirror(type, JoinSource.class);
        if (single != null) {
            mirrors.add(single);
        }
        AnnotationMirror container = mirror(type, JoinSources.class);
        if (container != null && explicit(container, "value") instanceof List<?> repeated) {
            for (Object value : repeated) {
                mirrors.add((AnnotationMirror) ((AnnotationValue) value).getValue());
            }
        }
        var joins = new ArrayList<JoinSourceDefinition>();
        for (AnnotationMirror mirror : mirrors) {
            TypeMirror source = type(mirror, "source");
            // A class that is no source model is left for the validator to report (MQ3044).
            ModelDefinition sourceModel = source instanceof DeclaredType declared && isSourceModel(declared.asElement())
                    ? read((TypeElement) declared.asElement(), Kind.SOURCE) : null;
            var on = new ArrayList<JoinOn>();
            if (explicit(mirror, "on") instanceof List<?> pairs) {
                for (Object pair : pairs) {
                    AnnotationMirror each = (AnnotationMirror) ((AnnotationValue) pair).getValue();
                    on.add(new JoinOn(explicit(each, "column") instanceof String column ? column : "",
                            explicit(each, "key") instanceof String key ? key : ""));
                }
            }
            joins.add(new JoinSourceDefinition(
                    explicit(mirror, "name") instanceof String name ? name : "", source, sourceModel,
                    explicit(mirror, "type") instanceof Element kind ? kind.getSimpleName().toString()
                            : JoinKind.LEFT.name(), on));
        }
        return joins;
    }

    /**
     * The class {@code @SourceModel(shape)} names, or {@code null} for {@code void.class} and for a model without it.
     */
    private static TypeMirror shape(AnnotationMirror annotation) {
        return type(annotation, "shape");
    }

    /** The value named by {@code @Discriminator} on {@code field}, or {@code null} when it carries none. */
    private static String discriminator(VariableElement field) {
        AnnotationMirror mirror = mirror(field, Discriminator.class);
        return mirror == null ? null
                : explicit(mirror, "value") instanceof String value ? value : "";
    }

    /**
     * What {@code @Window} says of {@code field}, or {@code null} when it carries none. The function and frame are
     * read as their constant names; {@code orderBy} entries are split into a path and a direction.
     */
    private static WindowDefinition window(VariableElement field) {
        AnnotationMirror mirror = mirror(field, Window.class);
        if (mirror == null) {
            return null;
        }
        String argument = explicit(mirror, "argument") instanceof String written ? written : "";
        return new WindowDefinition(
                explicit(mirror, "function") instanceof Element function ? function.getSimpleName().toString() : "",
                argument,
                strings(mirror, "partitionBy"),
                orders(strings(mirror, "orderBy")),
                explicit(mirror, "frame") instanceof Element frame ? frame.getSimpleName().toString() : "NONE");
    }

    /** The strings of a {@code String[]} annotation member, in order; empty when it was left at its default. */
    private static List<String> strings(AnnotationMirror mirror, String member) {
        if (!(explicit(mirror, member) instanceof List<?> values)) {
            return List.of();
        }
        var result = new ArrayList<String>(values.size());
        for (Object value : values) {
            result.add((String) ((AnnotationValue) value).getValue());
        }
        return result;
    }

    /** An {@code orderBy} entry, {@code "path"}, {@code "path asc"} or {@code "path desc"}, split into path and
     * direction; ascending by default. */
    private static List<WindowOrder> orders(List<String> entries) {
        var result = new ArrayList<WindowOrder>(entries.size());
        for (String entry : entries) {
            String trimmed = entry.trim();
            boolean descending = trimmed.endsWith(" desc");
            String path = trimmed.endsWith(" asc") ? trimmed.substring(0, trimmed.length() - 4)
                    : descending ? trimmed.substring(0, trimmed.length() - 5) : trimmed;
            result.add(new WindowOrder(path.trim(), !descending));
        }
        return result;
    }

    /** The annotation a model of {@code kind} carries. */
    static Class<? extends Annotation> annotation(Kind kind) {
        return switch (kind) {
            case QUERY -> QueryModel.class;
            case SOURCE -> SourceModel.class;
            case UPDATE -> UpdateModel.class;
            case INSERT -> InsertModel.class;
        };
    }

    /** Whether {@code element} is a source model, which an outer model may name as its {@code root} (R-PROC-39). */
    static boolean isSourceModel(Element element) {
        return mirror(element, SourceModel.class) != null;
    }

    /** Whether {@code element} is a JPA entity, read by name so the processor needs no JPA dependency (INV-7). */
    static boolean isEntity(Element element) {
        String entity = "jakarta.persistence.Entity";
        return element.getAnnotationMirrors().stream().anyMatch(mirror -> ((TypeElement) mirror.getAnnotationType()
                .asElement()).getQualifiedName().contentEquals(entity));
    }

    /** What {@code @Child} says of {@code field}, or {@code null} when it carries none. */
    private static ChildDefinition child(VariableElement field) {
        Child child = field.getAnnotation(Child.class);
        if (child == null) {
            return null;
        }
        boolean toMany = false;
        TypeMirror model = null;
        if (field.asType() instanceof DeclaredType container && container.getTypeArguments().size() == 1
                && container.getTypeArguments().get(0).getKind() == TypeKind.DECLARED) {
            Name name = ((TypeElement) container.asElement()).getQualifiedName();
            if (name.contentEquals(LIST) || name.contentEquals(OPTIONAL)) {
                toMany = name.contentEquals(LIST);
                model = container.getTypeArguments().get(0);
            }
        }
        return new ChildDefinition(List.of(child.key()), List.of(child.foreignKey()), child.through(), toMany,
                model);
    }

    /**
     * What {@code @Aggregate} says of {@code field}, or {@code null} when it carries none. One that names no
     * {@code expression} aggregates {@code bound}, a binding's, when there is one (R-PROC-29).
     */
    private static AggregateDefinition aggregate(VariableElement field, TypeMirror bound) {
        AnnotationMirror mirror = mirror(field, Aggregate.class);
        if (mirror == null) {
            return null;
        }
        TypeMirror expression = type(mirror, "expression");
        return new AggregateDefinition(
                explicit(mirror, "fn") instanceof Element fn ? fn.getSimpleName().toString() : "",
                explicit(mirror, "attribute") instanceof String attribute ? attribute : "",
                expression == null ? bound : expression,
                Boolean.TRUE.equals(explicit(mirror, "distinct")),
                strings(mirror, "orderBy"), type(mirror, "filter"),
                explicit(mirror, "scale") instanceof Integer scale && scale != -1 ? scale : null);
    }

    /**
     * The class the {@code @Computed} of {@code field} names; else {@code bound}, a binding's; else the {@code void}
     * type, which the validator reports (R-PROC-29).
     */
    private TypeMirror computed(VariableElement field, TypeMirror bound) {
        TypeMirror named = type(mirror(field, Computed.class), "value");
        return named != null ? named : bound != null ? bound : none;
    }

    /** The class {@code annotation} names as {@code member}, or {@code null} for none, {@code void.class} included. */
    private static TypeMirror type(AnnotationMirror annotation, String member) {
        return annotation != null && explicit(annotation, member) instanceof TypeMirror type
                && type.getKind() != TypeKind.VOID ? type : null;
    }

    /** The {@code @FilterColumn}s of {@code type}, written once or repeated inside a {@code @FilterColumns}. */
    private static List<FilterColumnDefinition> filterColumns(TypeElement type) {
        var mirrors = new ArrayList<AnnotationMirror>();
        AnnotationMirror single = mirror(type, FilterColumn.class);
        if (single != null) {
            mirrors.add(single);
        }
        AnnotationMirror container = mirror(type, FilterColumns.class);
        if (container != null && explicit(container, "value") instanceof List<?> repeated) {
            for (Object value : repeated) {
                mirrors.add((AnnotationMirror) ((AnnotationValue) value).getValue());
            }
        }
        var columns = new ArrayList<FilterColumnDefinition>();
        for (AnnotationMirror mirror : mirrors) {
            // An enum constant is read as its element, and only when written: a collection needs it explicit.
            Object joinType = explicit(mirror, "joinType");
            columns.add(new FilterColumnDefinition(
                    explicit(mirror, "name") instanceof String name ? name : "",
                    explicit(mirror, "path") instanceof String path ? path : "",
                    joinType instanceof Element constant ? constant.getSimpleName().toString() : JoinKind.LEFT.name(),
                    joinType instanceof Element,
                    explicit(mirror, "alias") instanceof String alias ? alias : "",
                    converter(mirror), type(mirror, TREAT),
                    explicit(mirror, "entityJoin") instanceof String join ? join : "", false));
        }
        return columns;
    }

    /** A {@code @Transient} field is no join, whatever else it carries. */
    private static boolean isJoin(VariableElement field) {
        return field.getAnnotation(Join.class) != null && field.getAnnotation(Transient.class) == null;
    }

    private static String joinAttribute(VariableElement field) {
        String attribute = field.getAnnotation(Join.class).attribute();
        return attribute.isEmpty() ? field.getSimpleName().toString() : attribute;
    }

    private static JoinDefinition join(VariableElement field, boolean sharesAttribute) {
        Join join = field.getAnnotation(Join.class);
        String name = field.getSimpleName().toString();
        String alias = join.alias().isEmpty() && sharesAttribute ? name : join.alias();
        TypeMirror nested = null;
        if (field.asType() instanceof DeclaredType optional && optional.getTypeArguments().size() == 1
                && ((TypeElement) optional.asElement()).getQualifiedName().contentEquals(OPTIONAL)
                && optional.getTypeArguments().get(0).getKind() == TypeKind.DECLARED) {
            nested = optional.getTypeArguments().get(0);
        }
        return new JoinDefinition(joinAttribute(field), join.type().name(),
                join.prefix().isEmpty() ? constantName(name) : join.prefix(), alias, nested, treat(field, Join.class));
    }

    /** The subtype named by an annotation's {@code treat} member, or {@code null} for none. */
    static TypeMirror treat(Element element, Class<? extends Annotation> annotation) {
        return type(mirror(element, annotation), TREAT);
    }

    /** The class named by {@code @Column(converter)}, or {@code null} when it is left at {@code void.class}. */
    private static TypeMirror converter(VariableElement field) {
        return converter(mirror(field, Column.class));
    }

    /** The class {@code annotation} names as its {@code converter}, or {@code null} for {@code void.class}. */
    private static TypeMirror converter(AnnotationMirror annotation) {
        return type(annotation, CONVERTER);
    }

    /** {@code customerId} as {@code CUSTOMER_ID}: an underscore at each lower-to-upper step and after an acronym. */
    static String constantName(String field) {
        var name = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char before = field.charAt(i - 1);
                boolean endsAcronym = Character.isUpperCase(before) && i + 1 < field.length()
                        && Character.isLowerCase(field.charAt(i + 1));
                if (Character.isLowerCase(before) || Character.isDigit(before) || endsAcronym) {
                    name.append('_');
                }
            }
            name.append(Character.toUpperCase(c));
        }
        return name.toString();
    }

    static AnnotationMirror mirror(Element element, Class<? extends Annotation> annotation) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement())
                    .getQualifiedName().contentEquals(annotation.getCanonicalName())) {
                return mirror;
            }
        }
        return null;
    }

    /** The value written for {@code member} on the annotation, or {@code null} when it was left at its default. */
    static Object explicit(AnnotationMirror mirror, String member) {
        for (var entry : mirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(member)) {
                AnnotationValue value = entry.getValue();
                return value.getValue();
            }
        }
        return null;
    }
}
