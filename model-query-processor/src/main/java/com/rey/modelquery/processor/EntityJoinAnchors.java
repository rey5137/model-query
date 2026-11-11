package com.rey.modelquery.processor;

import com.rey.modelquery.processor.EntityMetamodel.Step;
import com.rey.modelquery.processor.EntityMetamodel.Walk;
import com.rey.modelquery.processor.ModelDefinition.EntityJoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.FilterColumnDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * What a column-anchored entity join reads: a model column or {@code @FilterColumn} over the root, an entity join or
 * one of their to-one paths, and the static {@code when} value it fixes (R-PROC-46, R-SRC-25). Checks and generation
 * read this one resolution, so they refuse and emit the same anchors; {@link #chain} says which entity join an anchor
 * may read.
 *
 * @implSpec R-PROC-46, R-SRC-25, D-197, D-215
 */
final class EntityJoinAnchors {

    /**
     * An anchor column that resolved.
     *
     * @param field the model column it names, or {@code null} for a {@code @FilterColumn}
     * @param filter the filter column it names, or {@code null} for a model column
     * @param constant the generated constant the join reads
     * @param attribute the attribute path the column reads
     * @param type the column's model type
     */
    record Anchor(ModelField field, FilterColumnDefinition filter, String constant, String attribute,
            TypeMirror type) {

        /** The entity join the column reads, or {@code ""} for the root. */
        String entityJoin() {
            return field != null ? field.entityJoin() : filter.entityJoin();
        }

        /** The column's name in the model. */
        String name() {
            return field != null ? field.name() : filter.name();
        }
    }

    /**
     * An anchor, or why {@code name} is none; both {@code null} when a declared join's own check already reported
     * why the column can't resolve.
     */
    record Resolution(Anchor anchor, String problem) {}

    /**
     * A {@code when} value as the generated literal, or why it can't be one.
     *
     * @param literal the Java literal of the column's boxed type, or {@code null} when {@code problem} is set
     * @param problem why the value does not bind, or {@code null} when it does
     */
    record Value(CodeBlock literal, String problem) {}

    private EntityJoinAnchors() {}

    /**
     * Resolves {@code name} against {@code model}'s columns and filter columns: an unconverted basic column of the
     * root, of an entity join or of a to-one path from either, never one over a joined source (R-SRC-25); {@link
     * #chain} checks the entity join it reads (D-215).
     */
    static Resolution resolve(
            ModelDefinition model, EntityMetamodel metamodel, BuiltInConverters builtIns, String name) {
        ModelField field = model.columns().stream().filter(each -> each.name().equals(name)).findFirst().orElse(null);
        if (field != null) {
            return resolveField(model, metamodel, builtIns, field);
        }
        if (model.computed().stream().anyMatch(each -> each.name().equals(name))) {
            return problem("column '" + name + "' is an expression; an anchor reads a basic column");
        }
        FilterColumnDefinition filter = model.filterColumns().stream()
                .filter(each -> each.name().equals(name)).findFirst().orElse(null);
        if (filter != null) {
            return resolveFilter(model, metamodel, filter);
        }
        return new Resolution(null, "unknown anchor column '" + name + "'");
    }

    private static Resolution resolveField(
            ModelDefinition model, EntityMetamodel metamodel, BuiltInConverters builtIns, ModelField field) {
        String name = field.name();
        if (field.sourceColumn() != null) {
            return problem("column '" + name + "' reads the joined source '" + field.sourceColumn().join()
                    + "' of " + model.name() + "; an anchor reads the root or a to-one path from it");
        }
        if (field.treat() != null) {
            return problem("column '" + name + "' reads the treated " + ModelValidator.display(field.treat())
                    + ", which reads NULL on other subtypes (R-COL-35)");
        }
        TypeElement from = from(model, name, field.entityJoin());
        if (from == null) {
            return unknown(model, name, field.entityJoin());
        }
        Walk walk = metamodel.walk(from, field.attribute());
        Resolution walked = walked(field.attribute(), walk);
        if (walked != null) {
            return walked;
        }
        EntityAttribute last = walk.steps().get(walk.steps().size() - 1).attribute();
        if (field.converter() != null || builtIns.between(field.type(), last.type()) != null) {
            return problem("column '" + name + "' has a converter; the join compares database values");
        }
        return new Resolution(new Anchor(field, null, field.constant(), field.attribute(), field.type()), null);
    }

    private static Resolution resolveFilter(
            ModelDefinition model, EntityMetamodel metamodel, FilterColumnDefinition filter) {
        String name = filter.name();
        if (filter.treat() != null) {
            return problem("column '" + name + "' reads the treated " + ModelValidator.display(filter.treat())
                    + ", which reads NULL on other subtypes (R-COL-35)");
        }
        if (filter.converter() != null) {
            return problem("column '" + name + "' has a converter; the join compares database values");
        }
        TypeElement from = from(model, name, filter.entityJoin());
        if (from == null) {
            return unknown(model, name, filter.entityJoin());
        }
        Walk walk = metamodel.walk(from, filter.path());
        Resolution walked = walked(filter.path(), walk);
        if (walked != null) {
            return walked;
        }
        EntityAttribute last = walk.steps().get(walk.steps().size() - 1).attribute();
        return new Resolution(new Anchor(null, filter, filter.name(), filter.path(), last.type()), null);
    }

    /** The entity a column's path starts from: the root, or the entity of the join it names; {@code null} if none. */
    private static TypeElement from(ModelDefinition model, String name, String entityJoin) {
        if (entityJoin.isEmpty()) {
            return model.root();
        }
        return model.entityJoins().stream().filter(join -> join.name().equals(entityJoin))
                .map(join -> join.entity() instanceof DeclaredType declared
                        && QueryModelReader.isEntity((TypeElement) declared.asElement())
                        ? (TypeElement) declared.asElement() : null)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * Why column {@code name} can't read {@code entityJoin}: no join of that name, or none when the join is declared
     * but its entity is not a JPA {@code @Entity}, which that join's own check already reports.
     */
    private static Resolution unknown(ModelDefinition model, String name, String entityJoin) {
        return model.entityJoins().stream().anyMatch(join -> join.name().equals(entityJoin))
                ? new Resolution(null, null)
                : problem("column '" + name + "' reads the unknown entity join '" + entityJoin + "'");
    }

    /**
     * Why {@code anchor}, an anchor or the {@code when} column of {@code join}, can't read the entity join it reads,
     * or {@code null} when it reads the root or may read that join (R-SRC-25, D-215). Joins are created in
     * declaration order, so the join it reads is declared before {@code join}; a source-anchored join is created with
     * its source, so it anchors no other join, and a source-anchored join's {@code when} reads no join; an {@code
     * INNER} join on a {@code LEFT} one would drop every row the {@code LEFT} join misses.
     */
    static String chain(ModelDefinition model, EntityJoinDefinition join, Anchor anchor) {
        String read = anchor.entityJoin();
        if (read.isEmpty()) {
            return null;
        }
        String reads = "column '" + anchor.name() + "' reads the entity join '" + read + "'";
        if (!join.source().isEmpty()) {
            return reads + "; a source-anchored join's term reads the root or a to-one path from it, not another "
                    + "join";
        }
        var joins = model.entityJoins();
        int at = joins.indexOf(join);
        for (int i = 0; i < joins.size(); i++) {
            var earlier = joins.get(i);
            if (!earlier.name().equals(read)) {
                continue;
            }
            if (i == at) {
                return reads + ", its own root";
            }
            if (i > at) {
                return reads + ", declared after it; declare '" + read + "' first, as entity joins are created in "
                        + "declaration order";
            }
            if (!earlier.source().isEmpty()) {
                return reads + ", which is anchored on a joined source; only a join anchored on the root's columns "
                        + "may anchor another";
            }
            if (join.kind().equals("INNER") && earlier.kind().equals("LEFT")) {
                return reads + ", which is LEFT: this INNER join would drop every row '" + read + "' misses; "
                        + "declare '" + join.name() + "' LEFT, or '" + read + "' INNER";
            }
            return null;
        }
        return null;
    }

    private static Resolution walked(String attribute, Walk walk) {
        if (walk.problem() != null) {
            return problem("column '" + attribute + "' " + walk.problem());
        }
        for (Step step : walk.steps()) {
            if (step.attribute().kind() == EntityAttribute.Kind.COLLECTION) {
                return problem("column '" + attribute + "' traverses the to-many attribute '"
                        + step.attribute().name() + "'; an anchor reads the root or a to-one path from it");
            }
        }
        EntityAttribute last = walk.steps().get(walk.steps().size() - 1).attribute();
        if (last.kind() != EntityAttribute.Kind.BASIC) {
            return problem("column '" + attribute + "' ends at " + (last.kind() == EntityAttribute.Kind.TO_ONE
                    ? "a to-one association, which reads its id (R-COL-29)"
                    : "an embedded value")
                    + "; an anchor reads a basic column");
        }
        return null;
    }

    private static Resolution problem(String problem) {
        return new Resolution(null, problem);
    }

    /**
     * The literal of {@code value} as the boxed type of {@code type}, or why the value can't be one: R-COL-18's
     * bindable types bind, an enum, {@code Date}, {@code Calendar} or an entity does not (RFC 0022 B.6).
     */
    static Value whenValue(Types types, TypeMirror type, String value) {
        TypeMirror boxed = ProcessorTypes.boxed(types, type);
        if (boxed.getKind() != javax.lang.model.type.TypeKind.DECLARED) {
            return unbindable(value, ModelValidator.display(type));
        }
        TypeElement element = (TypeElement) ((DeclaredType) boxed).asElement();
        if (element.getKind() == ElementKind.ENUM) {
            return new Value(null, "an enum value does not bind as one statement parameter (R-COL-18)");
        }
        String name = element.getQualifiedName().toString();
        try {
            return switch (name) {
                case "java.lang.String" -> new Value(CodeBlock.of("$S", value), null);
                case "java.lang.Boolean" -> value.equals("true") || value.equals("false")
                        ? new Value(CodeBlock.of("$L", value), null) : unparsed(value, "boolean");
                case "java.lang.Integer" -> new Value(CodeBlock.of("$L", Integer.parseInt(value)), null);
                case "java.lang.Long" -> new Value(CodeBlock.of("$LL", Long.parseLong(value)), null);
                case "java.lang.Short" -> new Value(CodeBlock.of("(short) $L", Short.parseShort(value)), null);
                case "java.lang.Byte" -> new Value(CodeBlock.of("(byte) $L", Byte.parseByte(value)), null);
                case "java.lang.Float" -> {
                    Float.parseFloat(value);
                    yield new Value(CodeBlock.of("Float.valueOf($S)", value), null);
                }
                case "java.lang.Double" -> {
                    Double.parseDouble(value);
                    yield new Value(CodeBlock.of("Double.valueOf($S)", value), null);
                }
                case "java.lang.Character" -> value.length() == 1
                        ? new Value(CodeBlock.of("(char) $L", (int) value.charAt(0)), null)
                        : unparsed(value, "character");
                case "java.math.BigDecimal" -> new Value(
                        CodeBlock.of("new $T($S)", ClassName.get(BigDecimal.class), new BigDecimal(value)), null);
                case "java.math.BigInteger" -> new Value(
                        CodeBlock.of("new $T($S)", ClassName.get(BigInteger.class), new BigInteger(value)), null);
                case "java.util.UUID" -> new Value(
                        CodeBlock.of("$T.fromString($S)", ClassName.get(UUID.class), UUID.fromString(value)), null);
                case "java.time.LocalDate" -> parsed(LocalDate.parse(value), element);
                case "java.time.LocalTime" -> parsed(LocalTime.parse(value), element);
                case "java.time.LocalDateTime" -> parsed(LocalDateTime.parse(value), element);
                case "java.time.OffsetDateTime" -> parsed(OffsetDateTime.parse(value), element);
                case "java.time.OffsetTime" -> parsed(OffsetTime.parse(value), element);
                case "java.time.ZonedDateTime" -> parsed(ZonedDateTime.parse(value), element);
                case "java.time.Instant" -> parsed(Instant.parse(value), element);
                case "java.time.Year" -> parsed(Year.parse(value), element);
                case "java.time.YearMonth" -> parsed(YearMonth.parse(value), element);
                case "java.time.MonthDay" -> parsed(MonthDay.parse(value), element);
                case "java.time.Duration" -> parsed(Duration.parse(value), element);
                case "java.time.Period" -> parsed(Period.parse(value), element);
                default -> unbindable(value, element.getSimpleName().toString());
            };
        } catch (RuntimeException invalid) {
            return unparsed(value, element.getSimpleName().toString());
        }
    }

    private static Value parsed(Object parsed, TypeElement element) {
        return new Value(CodeBlock.of("$T.parse($S)", ClassName.get(element), parsed.toString()), null);
    }

    private static Value unbindable(String value, String type) {
        return new Value(null, "'" + value + "': a " + type + " value does not bind as one statement parameter "
                + "(R-COL-18)");
    }

    private static Value unparsed(String value, String type) {
        return new Value(null, "'" + value + "' is not a " + type);
    }
}
