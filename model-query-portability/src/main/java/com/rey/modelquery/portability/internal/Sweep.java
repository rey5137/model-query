package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.ValuesInsert;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The definitions a sweep runs: each {@code static final} field of a holder that holds a {@code ModelQuery},
 * {@code ModelUpdate}, {@code ModelDelete}, {@code ModelInsert}, {@code ValuesInsert} or {@code ModelPersist}, run with
 * one default operation and marked "first statement only" from the definition's public facts, erring towards
 * marking (D-138).
 *
 * @implSpec R-INS-12, D-138
 */
@Incubating
public final class Sweep {

    /** The id generation strategies whose id is drawn by a statement before the insert. */
    private static final Set<String> DRAWN = Set.of("SEQUENCE", "TABLE", "AUTO");

    private Sweep() {
    }

    /** Whether {@code value} is a definition a sweep runs. */
    public static boolean isDefinition(Object value) {
        return value instanceof ModelQuery || value instanceof ModelUpdate || value instanceof ModelDelete
                || value instanceof ModelInsert || value instanceof ModelPersist;
    }

    /**
     * Whether {@code field} is a definition field: {@code static final}, holding a definition.
     *
     * @throws IllegalStateException when the field cannot be read
     */
    public static boolean isDefinitionField(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && isDefinition(read(field));
    }

    /** The definition fields of {@code holder}, by name. */
    public static List<Field> definitionFields(Class<?> holder) {
        List<Field> fields = new ArrayList<>();
        for (Field field : holder.getDeclaredFields()) {
            if (!field.isSynthetic() && isDefinitionField(field)) {
                fields.add(field);
            }
        }
        fields.sort(Comparator.comparing(Field::getName));
        return fields;
    }

    /** The call a sweep makes for the definition in {@code field}, labelled {@code Holder.FIELD operation}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DryCall call(Field field) {
        Object definition = read(field);
        String name = field.getDeclaringClass().getSimpleName() + "." + field.getName();
        if (definition instanceof ModelQuery q) {
            Optional<FetchPlan<?>> plan = q.fetch();
            return DryCall.swept(name + " list", q.rootEntity(), x -> x.list(q, Limit.unlimited()),
                    (emf, profile) -> q.primaryKeyFirst().isPresent() || loadsChildren(plan));
        }
        if (definition instanceof ModelUpdate u) {
            return DryCall.swept(name + " update", u.rootEntity(), x -> x.update(u),
                    (emf, profile) -> u.chunkOptions().isPresent() || u.entityMode()
                            || !profile.targetTableInSubquery());
        }
        if (definition instanceof ModelDelete d) {
            return DryCall.swept(name + " delete", d.rootEntity(), x -> x.delete(d),
                    (emf, profile) -> d.chunkOptions().isPresent() || d.entityMode()
                            || !profile.targetTableInSubquery());
        }
        if (definition instanceof ValuesInsert v) {
            return DryCall.swept(name + " insert", v.rootEntity(), x -> x.insert(v),
                    (emf, profile) -> v.chunkOptions().isPresent() || idDrawn(emf, v.rootEntity()));
        }
        if (definition instanceof ModelInsert i) {
            return DryCall.swept(name + " insert", i.rootEntity(), x -> x.insert(i),
                    (emf, profile) -> i.chunkOptions().isPresent());
        }
        ModelPersist p = (ModelPersist) definition;
        // Every library refusal precedes em.persist, but an id drawn from a sequence or table is the first statement.
        return DryCall.swept(name + " persist", p.rootEntity(), x -> x.persist(p),
                (emf, profile) -> idDrawn(emf, p.rootEntity()) && !p.writesId(emf.getMetamodel()));
    }

    /** Whether {@code plan}, through its join plans, loads a child, whose statements follow the parent's. */
    private static boolean loadsChildren(Optional<FetchPlan<?>> plan) {
        return plan.map(Sweep::loadsChildren).orElse(false);
    }

    private static boolean loadsChildren(FetchPlan<?> plan) {
        return !plan.childLoads().isEmpty() || plan.joinPlans().stream().anyMatch(join -> loadsChildren(join.plan()));
    }

    /**
     * Whether {@code root}'s id is generated by a strategy that draws it with a statement of its own; read from the
     * {@code @GeneratedValue} annotation, so one declared in {@code orm.xml} is missed.
     */
    private static boolean idDrawn(EntityManagerFactory emf, Class<?> root) {
        for (SingularAttribute<?, ?> attribute : emf.getMetamodel().entity(root).getSingularAttributes()) {
            if (attribute.isId() && attribute.getJavaMember() instanceof AnnotatedElement member) {
                GeneratedValue generated = member.getAnnotation(GeneratedValue.class);
                if (generated != null && DRAWN.contains(generated.strategy().name())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object read(Field field) {
        if (!Modifier.isStatic(field.getModifiers())) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(field + " cannot be read: " + e, e);
        }
    }
}
