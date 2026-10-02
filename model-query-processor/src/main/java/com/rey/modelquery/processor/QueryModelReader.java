package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.ExcludeFromDefaults;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.FilterColumns;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.annotations.UpdateModel;
import com.rey.modelquery.processor.ModelDefinition.AggregateDefinition;
import com.rey.modelquery.processor.ModelDefinition.ChildDefinition;
import com.rey.modelquery.processor.ModelDefinition.FilterColumnDefinition;
import com.rey.modelquery.processor.ModelDefinition.JoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * Reads a {@code @QueryModel} or {@code @UpdateModel} type into a {@link ModelDefinition}. It checks nothing:
 * {@link ModelValidator} does.
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
    private static final String CONVERTER = "converter";
    private static final String OPTIONAL = "java.util.Optional";
    private static final String LIST = "java.util.List";

    private final Map<String, String> options;

    QueryModelReader(Map<String, String> options) {
        this.options = options;
    }

    /**
     * Reads {@code type}, or returns {@code null} when its {@code root} does not name a class, which the compiler
     * reports on its own.
     */
    ModelDefinition read(TypeElement type) {
        return read(type, mirror(type, QueryModel.class), false);
    }

    /** Reads the {@code @UpdateModel} {@code type}, or returns {@code null} as {@link #read} does. */
    ModelDefinition readUpdate(TypeElement type) {
        return read(type, mirror(type, UpdateModel.class), true);
    }

    private ModelDefinition read(TypeElement type, AnnotationMirror annotation, boolean updateModel) {
        if (!(explicit(annotation, ROOT) instanceof DeclaredType rootType) || rootType.getKind() != TypeKind.DECLARED) {
            return null;
        }
        // A name set on the annotation wins over the compilation-wide option, which wins over the default (D-44).
        // An update model has no suffix member, so the option alone sets it (D-69).
        String prefix = explicit(annotation, PREFIX) instanceof String set
                ? set : options.getOrDefault(PREFIX_OPTION, DEFAULT_PREFIX);
        String suffix = explicit(annotation, SUFFIX) instanceof String set
                ? set : options.getOrDefault(SUFFIX_OPTION, "");
        boolean selectSets = !updateModel && !Boolean.FALSE.equals(explicit(annotation, GENERATE_SELECT_SETS));
        boolean singleGroup = Boolean.TRUE.equals(explicit(annotation, SINGLE_GROUP));
        boolean generateChanges = Boolean.TRUE.equals(explicit(annotation, GENERATE_CHANGES));

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
            // Read even beside @Transient or @Join, so that the validator reports the pair (MQ3204).
            AggregateDefinition aggregate = child == null ? aggregate(field) : null;
            fields.add(new ModelField(
                    field,
                    field.getAnnotation(Transient.class) == null && join == null && aggregate == null
                            && child == null,
                    column == null || column.attribute().isEmpty() ? name : column.attribute(),
                    constantName(name),
                    field.getAnnotation(PrimaryKey.class) != null,
                    field.getAnnotation(ExcludeFromDefaults.class) != null,
                    column == null ? null : converter(field),
                    join,
                    aggregate,
                    child == null && field.getAnnotation(GroupBy.class) != null,
                    child));
        }
        return new ModelDefinition(
                type, (TypeElement) rootType.asElement(), prefix + type.getSimpleName() + suffix, selectSets,
                singleGroup, fields, filterColumns(type), updateModel, generateChanges);
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

    /** What {@code @Aggregate} says of {@code field}, or {@code null} when it carries none. */
    private static AggregateDefinition aggregate(VariableElement field) {
        AnnotationMirror mirror = mirror(field, Aggregate.class);
        if (mirror == null) {
            return null;
        }
        return new AggregateDefinition(
                explicit(mirror, "fn") instanceof Element fn ? fn.getSimpleName().toString() : "",
                explicit(mirror, "attribute") instanceof String attribute ? attribute : "",
                Boolean.TRUE.equals(explicit(mirror, "distinct")));
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
                    converter(mirror)));
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
                join.prefix().isEmpty() ? constantName(name) : join.prefix(), alias, nested);
    }

    /** The class named by {@code @Column(converter)}, or {@code null} when it is left at {@code void.class}. */
    private static TypeMirror converter(VariableElement field) {
        return converter(mirror(field, Column.class));
    }

    /** The class {@code annotation} names as its {@code converter}, or {@code null} for {@code void.class}. */
    private static TypeMirror converter(AnnotationMirror annotation) {
        return explicit(annotation, CONVERTER) instanceof TypeMirror converter
                && converter.getKind() != TypeKind.VOID ? converter : null;
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
    private static Object explicit(AnnotationMirror mirror, String member) {
        for (var entry : mirror.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(member)) {
                AnnotationValue value = entry.getValue();
                return value.getValue();
            }
        }
        return null;
    }
}
