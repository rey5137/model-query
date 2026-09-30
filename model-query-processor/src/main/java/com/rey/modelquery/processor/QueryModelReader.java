package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.ExcludeFromDefaults;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.FilterColumns;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Reads a {@code @QueryModel} type into a {@link ModelDefinition}. It checks nothing: {@link ModelValidator} does.
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
    private static final String GENERATE_COLUMN_SETS = "generateColumnSets";
    private static final String CONVERTER = "converter";

    private final Map<String, String> options;

    QueryModelReader(Map<String, String> options) {
        this.options = options;
    }

    /**
     * Whether {@code type} uses an annotation whose generation is not built yet: a converter or {@code @Join}
     * (M4.3), {@code @FilterColumn} (M4.4), {@code @Aggregate} or {@code @GroupBy} (M4.5). Such a model is left
     * alone rather than generated without them.
     */
    static boolean usesLaterFeature(TypeElement type) {
        if (type.getAnnotation(FilterColumn.class) != null || type.getAnnotation(FilterColumns.class) != null) {
            return true;
        }
        for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
            if (field.getAnnotation(Join.class) != null || field.getAnnotation(Aggregate.class) != null
                    || field.getAnnotation(GroupBy.class) != null) {
                return true;
            }
            AnnotationMirror column = mirror(field, Column.class);
            if (column != null && explicit(column, CONVERTER) instanceof TypeMirror converter
                    && converter.getKind() != TypeKind.VOID) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads {@code type}, or returns {@code null} when its {@code root} does not name a class, which the compiler
     * reports on its own.
     */
    ModelDefinition read(TypeElement type) {
        AnnotationMirror queryModel = mirror(type, QueryModel.class);
        if (!(explicit(queryModel, ROOT) instanceof DeclaredType rootType) || rootType.getKind() != TypeKind.DECLARED) {
            return null;
        }
        // A name set on the annotation wins over the compilation-wide option, which wins over the default (D-44).
        String prefix = explicit(queryModel, PREFIX) instanceof String set
                ? set : options.getOrDefault(PREFIX_OPTION, DEFAULT_PREFIX);
        String suffix = explicit(queryModel, SUFFIX) instanceof String set
                ? set : options.getOrDefault(SUFFIX_OPTION, "");
        boolean columnSets = !Boolean.FALSE.equals(explicit(queryModel, GENERATE_COLUMN_SETS));

        var fields = new ArrayList<ModelField>();
        for (VariableElement field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            String name = field.getSimpleName().toString();
            fields.add(new ModelField(
                    field,
                    field.getAnnotation(Transient.class) == null,
                    column == null || column.attribute().isEmpty() ? name : column.attribute(),
                    constantName(name),
                    field.getAnnotation(PrimaryKey.class) != null,
                    field.getAnnotation(ExcludeFromDefaults.class) != null));
        }
        return new ModelDefinition(
                type, (TypeElement) rootType.asElement(), prefix + type.getSimpleName() + suffix, columnSets, fields);
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

    private static AnnotationMirror mirror(Element element, Class<? extends Annotation> annotation) {
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
