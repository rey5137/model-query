package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A configuration's {@link WriteAssignment}s that apply to one root, those naming the root class or a superclass of
 * it, checked against one factory's metamodel (R-WRT-49).
 */
final class WriteAssignments {

    /**
     * The roots' resolved assignments per factory, then per configured list: checked once per root per factory
     * (D-61), since another factory may map the root differently and another configuration hold other assignments.
     * Weak on both levels, so neither a closed factory nor a dropped configuration stays reachable.
     */
    private static final Map<EntityManagerFactory, Map<List<WriteAssignment>, Map<Class<?>, WriteAssignments>>>
            RESOLVED = Collections.synchronizedMap(new WeakHashMap<>());

    private static final WriteAssignments NONE = new WriteAssignments(List.of());

    /** An assignment that applies to the root, with its attribute's Java type, boxed. */
    private record Resolved(WriteAssignment assignment, Class<?> attributeType) {

        boolean appliesTo(WriteKind write) {
            return assignment.kind() == write || assignment.kind() == WriteKind.INSERT_AND_UPDATE;
        }
    }

    /** One attribute a write sets: its path, its Java type, boxed, and the value the supplier returned. */
    record Written(String path, Class<?> type, Object value) {}

    private final List<Resolved> resolved;

    private WriteAssignments(List<Resolved> resolved) {
        this.resolved = resolved;
    }

    /**
     * The assignments of {@code configured} that apply to {@code root}, checked against {@code metamodel} the first
     * time per root on {@code emf}, before any statement.
     *
     * @throws ModelQueryDefinitionException {@code MQ1611} for a path that is unknown or names an id, a
     *     {@code @Version}, a collection, a to-one or a whole embeddable, or for two assignments of overlapping kinds
     *     for one path; {@code MQ1612} for a type the attribute cannot take after boxing
     */
    static WriteAssignments of(EntityManagerFactory emf, Metamodel metamodel, Class<?> root,
            List<WriteAssignment> configured) {
        if (configured.isEmpty()) {
            return NONE;
        }
        Map<Class<?>, WriteAssignments> roots;
        synchronized (RESOLVED) {
            roots = RESOLVED.computeIfAbsent(emf, factory -> Collections.synchronizedMap(new WeakHashMap<>()))
                    .computeIfAbsent(configured, list -> new ConcurrentHashMap<>());
        }
        // Not stored when the check throws, so it runs again next time
        return roots.computeIfAbsent(root, type -> resolve(metamodel.entity(type), configured));
    }

    private static WriteAssignments resolve(EntityType<?> root, List<WriteAssignment> configured) {
        var resolved = new ArrayList<Resolved>();
        for (WriteAssignment assignment : configured) {
            if (!assignment.entity().isAssignableFrom(root.getJavaType())) {
                continue;
            }
            Class<?> attributeType = boxed(attribute(root, assignment).getJavaType());
            if (!attributeType.isAssignableFrom(boxed(assignment.type()))) {
                throw new ModelQueryDefinitionException(MqCode.MQ1612, assignment + ": "
                        + describe(root, assignment) + " is a " + attributeType.getSimpleName() + ", which a "
                        + assignment.type().getSimpleName() + " is not");
            }
            for (Resolved earlier : resolved) {
                if (earlier.assignment().attribute().equals(assignment.attribute())
                        && overlap(earlier.assignment().kind(), assignment.kind())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1611, assignment + ": "
                            + describe(root, assignment) + " is assigned by " + earlier.assignment() + " already, "
                            + "on the writes both kinds name; give one path one assignment per kind of write");
                }
            }
            resolved.add(new Resolved(assignment, attributeType));
        }
        return resolved.isEmpty() ? NONE : new WriteAssignments(List.copyOf(resolved));
    }

    /** The basic singular attribute {@code assignment}'s path names below {@code root}, else {@code MQ1611}. */
    private static SingularAttribute<?, ?> attribute(EntityType<?> root, WriteAssignment assignment) {
        ManagedType<?> type = root;
        String[] segments = assignment.attribute().split("\\.");
        for (int i = 0; ; i++) {
            Attribute<?, ?> attribute;
            try {
                attribute = type.getAttribute(segments[i]);
            } catch (IllegalArgumentException e) {
                throw unassignable(root, assignment, "names no attribute " + segments[i] + " of "
                        + type.getJavaType().getSimpleName());
            }
            if (!(attribute instanceof SingularAttribute<?, ?> singular)) {
                throw unassignable(root, assignment, "names the collection " + attribute.getName());
            }
            if (singular.isId()) {
                throw unassignable(root, assignment, "names the id " + attribute.getName());
            }
            if (singular.isVersion()) {
                throw unassignable(root, assignment, "names the @Version " + attribute.getName());
            }
            if (singular.isAssociation()) {
                throw unassignable(root, assignment, "names the to-one " + attribute.getName());
            }
            boolean embedded = singular.getType() instanceof ManagedType<?>;
            if (i == segments.length - 1) {
                if (embedded) {
                    throw unassignable(root, assignment, "names the whole embeddable " + attribute.getName()
                            + "; name one of its attributes");
                }
                return singular;
            }
            if (!embedded) {
                throw unassignable(root, assignment, "goes on past the basic attribute " + attribute.getName());
            }
            type = (ManagedType<?>) singular.getType();
        }
    }

    /** Whether two kinds share a write: any two but {@code INSERT} and {@code UPDATE}. */
    private static boolean overlap(WriteKind a, WriteKind b) {
        return a == b || a == WriteKind.INSERT_AND_UPDATE || b == WriteKind.INSERT_AND_UPDATE;
    }

    private static ModelQueryDefinitionException unassignable(EntityType<?> root, WriteAssignment assignment,
            String why) {
        return new ModelQueryDefinitionException(MqCode.MQ1611, assignment + ": " + describe(root, assignment) + " "
                + why + "; a write assignment names a basic singular attribute, possibly through embeddables");
    }

    private static String describe(EntityType<?> root, WriteAssignment assignment) {
        return root.getJavaType().getSimpleName() + "." + assignment.attribute();
    }

    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }

    /**
     * The paths of the assignments a {@code write} applies, in configured order, each assignment applying to the
     * writes its kind names: {@code INSERT} or {@code UPDATE}.
     */
    List<String> paths(WriteKind write) {
        return resolved.stream().filter(r -> r.appliesTo(write)).map(r -> r.assignment().attribute()).toList();
    }

    /** One write execution, which calls each supplier at most once (R-WRT-49). */
    Execution execution() {
        return new Execution();
    }

    /** The values of one write execution: each supplier called the first time its value is needed, then kept. */
    final class Execution {

        private final Map<WriteAssignment, Object> values = new IdentityHashMap<>();

        /**
         * The attributes a {@code write}, {@code INSERT} or {@code UPDATE}, sets, in configured order, those of
         * {@code set} left out: the definition sets them itself, which wins (R-WRT-49).
         *
         * @throws ModelQueryDefinitionException {@code MQ1612} when a supplier returns {@code null} or a value of
         *     another type than the one declared
         */
        List<Written> values(WriteKind write, Collection<String> set) {
            var written = new ArrayList<Written>();
            for (Resolved r : resolved) {
                if (r.appliesTo(write) && !set.contains(r.assignment().attribute())) {
                    written.add(new Written(r.assignment().attribute(), r.attributeType(), value(r.assignment())));
                }
            }
            return written;
        }

        private Object value(WriteAssignment assignment) {
            if (values.containsKey(assignment)) {
                return values.get(assignment);
            }
            Object value = assignment.value().get();
            if (value == null || !boxed(assignment.type()).isInstance(value)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1612, assignment + ": its supplier returned "
                        + (value == null ? "null" : "a " + value.getClass().getSimpleName())
                        + ", where a write needs a " + assignment.type().getSimpleName());
            }
            values.put(assignment, value);
            return value;
        }
    }

    /** {@code written} by path, in order, as the conflict update takes them. */
    static Map<String, Object> byPath(List<Written> written) {
        var byPath = new LinkedHashMap<String, Object>();
        written.forEach(w -> byPath.put(w.path(), w.value()));
        return byPath;
    }
}
