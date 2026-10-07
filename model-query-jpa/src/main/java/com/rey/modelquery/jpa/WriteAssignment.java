package com.rey.modelquery.jpa;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A value the server sets on every write of an entity, such as an audit timestamp, held by
 * {@link ModelQueryConfig#writeAssignments(java.util.Collection)} (R-WRT-49). It applies to the named entity class and
 * its subclasses, so one on a {@code @MappedSuperclass} covers every entity extending it, for the writes its
 * {@link WriteKind} names. An explicit {@code set}, {@code setNull} or change-set field for the same attribute wins,
 * and an update with nothing else to write stays a no-op.
 *
 * <p>The attribute path names a basic singular attribute of the root, possibly through embeddables
 * ({@code "audit.updatedAt"}). It is checked on the first write per root per {@code EntityManagerFactory}, before any
 * statement: an unknown path, an id, a {@code @Version}, a collection, a to-one, a whole embeddable, or two assignments
 * of overlapping kinds for one root and path, throws {@code MQ1611}; a type the attribute cannot take, after boxing,
 * {@code MQ1612}.
 *
 * <p>The supplier is called once per write execution, so every chunk and row of one write gets the same value, and
 * again on each resume after a {@code ChunkedWriteException}. It may be called from several threads at once and must
 * be thread-safe; a {@code Clock} keeps tests deterministic. A supplier returning {@code null} or a value of the wrong
 * type throws {@code MQ1612} at execution, before any statement. Under {@code persist} and entity mode the value is
 * set on the entity before the flush, so an entity callback that sets the same attribute wins.
 *
 * @implSpec R-WRT-49
 */
@Incubating
public final class WriteAssignment {

    private final Class<?> entity;
    private final String attribute;
    private final Class<?> type;
    private final WriteKind kind;
    private final Supplier<?> value;

    private WriteAssignment(Class<?> entity, String attribute, Class<?> type, WriteKind kind, Supplier<?> value) {
        this.entity = entity;
        this.attribute = attribute;
        this.type = type;
        this.kind = kind;
        this.value = value;
    }

    /**
     * An assignment of {@code value}'s result to {@code attribute} of {@code entity} and its subclasses, on the writes
     * {@code kind} names.
     *
     * @param entity the entity class, or a {@code @MappedSuperclass} its roots extend
     * @param attribute the attribute path, dot-separated through embeddables
     * @param type the attribute's type; a primitive attribute may be named by its wrapper
     * @param kind the writes it applies to
     * @param value the value's supplier, called once per write execution
     * @param <A> the attribute's type
     * @throws NullPointerException for a {@code null} argument
     * @throws IllegalArgumentException for a primitive or array {@code entity}, or an {@code attribute} that is not
     *     dot-separated Java identifiers
     */
    @Incubating
    public static <A> WriteAssignment of(Class<?> entity, String attribute, Class<A> type, WriteKind kind,
            Supplier<? extends A> value) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (entity.isPrimitive() || entity.isArray()) {
            throw new IllegalArgumentException(entity.getName() + " is not an entity class");
        }
        if (!isPath(attribute)) {
            throw new IllegalArgumentException(entity.getSimpleName() + ": \"" + attribute + "\" is not an attribute "
                    + "path; name the attribute, dot-separated through embeddables");
        }
        return new WriteAssignment(entity, attribute, type, kind, value);
    }

    private static boolean isPath(String attribute) {
        if (attribute.isEmpty()) {
            return false;
        }
        for (String segment : attribute.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
                return false;
            }
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The entity class it applies to, with its subclasses. */
    @Incubating
    public Class<?> entity() {
        return entity;
    }

    /** The attribute path, dot-separated through embeddables. */
    @Incubating
    public String attribute() {
        return attribute;
    }

    /** The attribute's type, as given. */
    @Incubating
    public Class<?> type() {
        return type;
    }

    /** The writes it applies to. */
    @Incubating
    public WriteKind kind() {
        return kind;
    }

    /** The value's supplier, which the executor calls once per write execution. */
    Supplier<?> value() {
        return value;
    }

    @Override
    public String toString() {
        return "WriteAssignment[" + entity.getSimpleName() + "." + attribute + ": " + type.getSimpleName() + ", "
                + kind + "]";
    }
}
