package com.rey.modelquery.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Reads the persistent attributes of a JPA entity from its {@code TypeElement}, in source or on the classpath, the
 * way a provider would: by access type, through mapped superclasses and into embeddables. JPA annotations are read by
 * name, so the processor needs no JPA dependency and no generated metamodel.
 *
 * @implSpec R-GEN-01, R-GEN-02
 */
final class EntityMetamodel {

    private static final String JPA = "jakarta.persistence.";
    private static final String ACCESS = JPA + "Access";
    private static final String PROPERTY = "PROPERTY";
    private static final List<String> MANAGED = List.of(JPA + "Entity", JPA + "MappedSuperclass", JPA + "Embeddable");
    private static final List<String> IDS = List.of(JPA + "Id", JPA + "EmbeddedId");
    private static final List<String> EMBEDDED = List.of(JPA + "Embedded", JPA + "EmbeddedId");
    private static final List<String> TO_ONE = List.of(JPA + "ManyToOne", JPA + "OneToOne");
    private static final List<String> TO_MANY = List.of(JPA + "OneToMany", JPA + "ManyToMany");
    private static final String ELEMENT_COLLECTION = JPA + "ElementCollection";
    private static final String EMBEDDABLE = JPA + "Embeddable";
    private static final String TRANSIENT = JPA + "Transient";
    private static final String VERSION = JPA + "Version";
    private static final String ID_CLASS = JPA + "IdClass";
    private static final String GENERATED_VALUE = JPA + "GeneratedValue";
    private static final List<String> GENERATOR_META = List.of("org.hibernate.annotations.IdGeneratorType",
            "org.hibernate.annotations.ValueGenerationType");
    private static final String HIBERNATE_GENERATED = "org.hibernate.annotations.Generated";
    private static final String JOIN_FORMULA = "org.hibernate.annotations.JoinFormula";
    private static final String JOIN_COLUMN_OR_FORMULA = "org.hibernate.annotations.JoinColumnOrFormula";
    private static final String JOIN_COLUMN_OR_FORMULAS = "org.hibernate.annotations.JoinColumnsOrFormulas";
    private static final String NOT_FOUND = "org.hibernate.annotations.NotFound";
    private static final String HIBERNATE = "org.hibernate.annotations.";
    private static final List<String> COLUMNS = List.of(JPA + "Column", JPA + "JoinColumn");

    private final Types types;
    private final Elements elements;
    private final Map<TypeElement, Id> idCache = new HashMap<>();

    EntityMetamodel(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    /**
     * Where a column's attribute path ends, or why it does not resolve.
     *
     * @param attribute the attribute the path ends at, or {@code null} when it does not resolve
     * @param owner the simple name of the type that declares {@code attribute}: the root, or an embeddable
     * @param problem what is wrong with the path, worded to follow {@code "Model.field: "}; {@code null} when resolved
     * @param association whether the path does not resolve because a segment before its last is an association
     */
    record Resolution(EntityAttribute attribute, String owner, String problem, boolean association) {}

    /**
     * Resolves {@code path} from {@code root}. Every segment but the last must be an embedded value: a column's path
     * never crosses an association (R-PROC-06, D-41).
     *
     * @implSpec R-GEN-03
     */
    Resolution resolve(TypeElement root, String path) {
        Walk walk = walk(root, path);
        // Every step but the path's last is a segment the path goes through: all of them when the walk stopped early.
        int through = walk.problem() == null ? walk.steps().size() - 1 : walk.steps().size();
        for (Step step : walk.steps().subList(0, through)) {
            if (step.attribute().kind() == EntityAttribute.Kind.TO_ONE
                    || step.attribute().kind() == EntityAttribute.Kind.COLLECTION) {
                return new Resolution(null, null, "'" + step.attribute().name() + "' on " + step.owner()
                        + " is an association, so '" + path + "' needs @Join or @FilterColumn", true);
            }
        }
        if (walk.problem() != null) {
            return new Resolution(null, null, walk.problem(), false);
        }
        Step last = walk.steps().get(walk.steps().size() - 1);
        return new Resolution(last.attribute(), last.owner(), null, false);
    }

    /**
     * One segment of a path.
     *
     * @param owner the simple name of the type the segment was looked up on
     * @param attribute the attribute the segment names
     */
    record Step(String owner, EntityAttribute attribute) {}

    /**
     * A path followed segment by segment, through embedded values and across associations.
     *
     * @param steps the segments that resolved, in order: all of them when {@code problem} is {@code null}
     * @param problem why the segment after {@code steps} does not resolve, worded to follow {@code "Model.field: "}
     */
    record Walk(List<Step> steps, String problem) {}

    /**
     * Follows {@code path} from {@code root}, as a {@code @FilterColumn} may write it: a segment before the last is
     * an embedded value or an association (R-PROC-10).
     *
     * @implSpec R-GEN-03
     */
    Walk walk(TypeElement root, String path) {
        String[] segments = path.split("\\.", -1);
        DeclaredType owner = (DeclaredType) root.asType();
        boolean property = false;
        var steps = new ArrayList<Step>();
        for (int i = 0; ; i++) {
            List<DeclaredType> hierarchy = hierarchy(owner);
            property = defaultsToProperty(hierarchy, property);
            EntityAttribute found = attributes(hierarchy, property).get(segments[i]);
            String ownerName = owner.asElement().getSimpleName().toString();
            if (found == null) {
                return new Walk(steps, "no attribute '" + segments[i] + "' on " + ownerName);
            }
            steps.add(new Step(ownerName, found));
            if (i == segments.length - 1) {
                return new Walk(steps, null);
            }
            if (found.kind() == EntityAttribute.Kind.EMBEDDED) {
                owner = (DeclaredType) found.type();
            } else if (found.joinable()) {
                owner = found.target();
                // An entity's access type is its own: only an embeddable inherits its owner's.
                property = false;
            } else if (found.kind() == EntityAttribute.Kind.BASIC) {
                return new Walk(steps, "'" + segments[i] + "' on " + ownerName
                        + " is not an embedded value, so it has no attribute '" + segments[i + 1] + "'");
            } else {
                return new Walk(steps, "'" + segments[i] + "' on " + ownerName + " has no entity to join, so '"
                        + path + "' can't go through it");
            }
        }
    }

    /**
     * The id of an entity, as a bulk write keys on it (api/14 R-WRT-08).
     *
     * @param attributes its {@code @Id} attributes, its {@code @IdClass} attributes included, or its
     *     {@code @EmbeddedId}
     * @param components the {@code @EmbeddedId}'s components as dotted paths, {@code id.warehouseId}; empty for
     *     any other id
     * @param type the id's type as {@code getReference} takes it: the one attribute's, else the {@code @IdClass};
     *     {@code null} when the entity declares neither
     * @param generated whether an {@code @Id} attribute carries {@code @GeneratedValue} or a generator annotation
     *     (one meta-annotated {@code @IdGeneratorType} or {@code @ValueGenerationType}); a generator declared in
     *     {@code orm.xml} is not seen
     */
    record Id(Set<String> attributes, Set<String> components, TypeMirror type, boolean generated) {

        /** Whether {@code paths} names exactly this id, as {@code MQ1608} compares a bulk write's key. */
        boolean is(Set<String> paths) {
            return paths.equals(attributes) || !components.isEmpty() && paths.equals(components);
        }

        /** Whether {@code path} names this id or one of its parts. */
        boolean covers(String path) {
            return attributes.contains(path) || components.contains(path);
        }

        /** The id as a diagnostic names it: {@code 'id'}, or {@code 'warehouseId', 'productId'}. */
        String label() {
            return attributes.stream().sorted().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
        }
    }

    /**
     * The id of {@code root}, read once per instance (one processing round): its {@code @Id} attributes, its {@code @IdClass} attributes, its {@code @EmbeddedId}
     * and that id's components, as the engine checks a bulk write's key on first execution ({@code MQ1608}).
     *
     * @implSpec R-GEN-22
     */
    Id id(TypeElement root) {
        Id known = idCache.get(root);
        if (known != null) {
            return known;
        }
        Id id = readId(root);
        idCache.put(root, id);
        return id;
    }

    private Id readId(TypeElement root) {
        List<DeclaredType> hierarchy = hierarchy((DeclaredType) root.asType());
        boolean property = defaultsToProperty(hierarchy, false);
        var ids = new LinkedHashSet<String>();
        TypeMirror idClass = null;
        boolean generated = false;
        for (DeclaredType type : hierarchy) {
            for (AnnotationMirror mirror : type.asElement().getAnnotationMirrors()) {
                if (isNamed(mirror, ID_CLASS)) {
                    idClass = (TypeMirror) value(mirror, "value");
                }
            }
            for (Element member : type.asElement().getEnclosedElements()) {
                if (hasAny(member, IDS)) {
                    ids.add(member.getKind() == ElementKind.METHOD
                            ? propertyName((ExecutableElement) member) : member.getSimpleName().toString());
                    generated |= hasAny(member, List.of(GENERATED_VALUE)) || hasGeneratorAnnotation(member);
                }
            }
        }
        Map<String, EntityAttribute> attributes = attributes(hierarchy, property);
        EntityAttribute id = ids.size() == 1 ? attributes.get(ids.iterator().next()) : null;
        TypeMirror type = idClass != null ? idClass : id == null ? null : id.type();
        if (id == null || id.kind() != EntityAttribute.Kind.EMBEDDED) {
            return new Id(Set.copyOf(ids), Set.of(), type, generated);
        }
        List<DeclaredType> embeddable = hierarchy((DeclaredType) id.type());
        Set<String> components = attributes(embeddable, defaultsToProperty(embeddable, property)).keySet().stream()
                .map(component -> id.name() + "." + component)
                .collect(Collectors.toSet());
        return new Id(Set.copyOf(ids), components, type, generated);
    }

    /** The collection associations of {@code root} itself, in declaration order (R-PROC-13). */
    List<EntityAttribute> collections(TypeElement root) {
        List<DeclaredType> hierarchy = hierarchy((DeclaredType) root.asType());
        return attributes(hierarchy, defaultsToProperty(hierarchy, false)).values().stream()
                .filter(attribute -> attribute.kind() == EntityAttribute.Kind.COLLECTION && attribute.joinable())
                .toList();
    }

    /** {@code type} and its managed superclasses, topmost first, each with its type arguments applied. */
    private List<DeclaredType> hierarchy(DeclaredType type) {
        var chain = new ArrayList<DeclaredType>();
        chain.add(type);
        TypeMirror current = type;
        while (true) {
            List<? extends TypeMirror> supertypes = types.directSupertypes(current);
            // A class's superclass is its first direct supertype; Object has none.
            if (supertypes.isEmpty() || supertypes.get(0).getKind() != TypeKind.DECLARED) {
                return chain;
            }
            current = supertypes.get(0);
            // A superclass that is neither entity nor mapped superclass contributes no persistent state.
            if (hasAny(((DeclaredType) current).asElement(), MANAGED)) {
                chain.add(0, (DeclaredType) current);
            }
        }
    }

    /**
     * The hierarchy's default access type: property access when the identifier is mapped on a getter, else
     * {@code inherited}, which is field access for an entity and the owner's access for an embeddable.
     */
    private static boolean defaultsToProperty(List<DeclaredType> hierarchy, boolean inherited) {
        for (DeclaredType type : hierarchy) {
            for (Element member : type.asElement().getEnclosedElements()) {
                if (hasAny(member, IDS)) {
                    return member.getKind() == ElementKind.METHOD;
                }
            }
        }
        return inherited;
    }

    private Map<String, EntityAttribute> attributes(List<DeclaredType> hierarchy, boolean propertyDefault) {
        var attributes = new LinkedHashMap<String, EntityAttribute>();
        for (DeclaredType type : hierarchy) {
            Boolean declared = propertyAccess(type.asElement());
            boolean property = declared != null ? declared : propertyDefault;
            for (Element member : type.asElement().getEnclosedElements()) {
                if (member.getModifiers().contains(Modifier.STATIC) || hasAny(member, List.of(TRANSIENT))) {
                    continue;
                }
                Boolean explicit = propertyAccess(member);
                boolean viaProperty = explicit != null ? explicit : property;
                if (member.getKind() == ElementKind.FIELD) {
                    if (!viaProperty && !member.getModifiers().contains(Modifier.TRANSIENT)) {
                        String name = member.getSimpleName().toString();
                        attributes.put(name, attribute(name, types.asMemberOf(type, member), member));
                    }
                } else if (member.getKind() == ElementKind.METHOD && viaProperty) {
                    String name = propertyName((ExecutableElement) member);
                    if (name != null) {
                        TypeMirror returned = ((ExecutableType) types.asMemberOf(type, member)).getReturnType();
                        attributes.put(name, attribute(name, returned, member));
                    }
                }
            }
        }
        return attributes;
    }

    private EntityAttribute attribute(String name, TypeMirror type, Element member) {
        boolean version = hasAny(member, List.of(VERSION));
        boolean updatable = !columnSaysFalse(member, "updatable");
        boolean insertable = !columnSaysFalse(member, "insertable");
        if (hasAny(member, TO_ONE)) {
            String mappedBy = member.getAnnotationMirrors().stream()
                    .filter(mirror -> TO_ONE.stream().anyMatch(toOne -> isNamed(mirror, toOne)))
                    .map(mirror -> value(mirror, "mappedBy"))
                    .filter(value -> value instanceof String named && !named.isEmpty())
                    .map(String.class::cast)
                    .findFirst().orElse(null);
            return new EntityAttribute(
                    name, type, EntityAttribute.Kind.TO_ONE, entity(type), version, updatable, insertable, mappedBy,
                    false, idFormRefusal(member, mappedBy),
                    hasAny(member, List.of(JPA + "MapsId")));
        }
        if (hasAny(member, TO_MANY)) {
            // The element of a Collection<E>, the value of a Map<K, E>.
            List<? extends TypeMirror> arguments = type.getKind() == TypeKind.DECLARED
                    ? ((DeclaredType) type).getTypeArguments() : List.of();
            DeclaredType target = arguments.isEmpty() ? null : entity(arguments.get(arguments.size() - 1));
            return new EntityAttribute(
                    name, type, EntityAttribute.Kind.COLLECTION, target, version, updatable, insertable, null, false,
                    null, false);
        }
        if (hasAny(member, List.of(ELEMENT_COLLECTION))) {
            return new EntityAttribute(
                    name, type, EntityAttribute.Kind.COLLECTION, null, version, updatable, insertable, null, false,
                    null, false);
        }
        boolean embedded = type.getKind() == TypeKind.DECLARED && (hasAny(member, EMBEDDED)
                || hasAny(((DeclaredType) type).asElement(), List.of(EMBEDDABLE)));
        return new EntityAttribute(name, type, embedded ? EntityAttribute.Kind.EMBEDDED : EntityAttribute.Kind.BASIC,
                null, version, updatable, insertable, null, filledByDatabase(member), null, false);
    }

    /**
     * Whether {@code field}, a column over the to-one {@code attribute}, holds the target's id rather than the entity:
     * its type, or its converter's attribute type, is not the to-one's own type (D-124, D-136). A converter that
     * does not read as a {@code ColumnConverter} is the whole-entity form, which {@code MQ3014} reports.
     */
    boolean byId(ModelDefinition.ModelField field, EntityAttribute attribute) {
        if (attribute.kind() != EntityAttribute.Kind.TO_ONE) {
            return false;
        }
        TypeMirror held = field.type();
        if (field.converter() != null) {
            ConverterType converter = ConverterType.of(types, field.converter());
            if (converter == null) {
                return false;
            }
            held = converter.attribute();
        }
        return !types.isSameType(ProcessorTypes.boxed(types, held), ProcessorTypes.boxed(types, attribute.type()));
    }

    /**
     * Why the to-one {@code member} can't be read by its id, or {@code null} when its mapping can: only the owning
     * side joining the target's primary key, with no formula and no {@code @NotFound}. Annotations are read by name,
     * and a {@code referencedColumnName} is refused whatever it names, since the processor can't tell it from the
     * primary key's column (R-PROC-33, D-136).
     */
    private static String idFormRefusal(Element member, String mappedBy) {
        if (mappedBy != null) {
            return "it is the inverse side (mappedBy = \"" + mappedBy + "\"), which has no foreign key of its own";
        }
        for (AnnotationMirror mirror : member.getAnnotationMirrors()) {
            if (isNamed(mirror, JPA + "JoinColumns")) {
                return "it has " + simple(mirror) + ", a composite join";
            }
            if (isNamed(mirror, JOIN_FORMULA) || isNamed(mirror, JOIN_COLUMN_OR_FORMULA)
                    || isNamed(mirror, JOIN_COLUMN_OR_FORMULAS)) {
                return "it has " + simple(mirror) + ", which joins on a formula";
            }
            if (isNamed(mirror, NOT_FOUND)) {
                return "it has @NotFound, which makes Hibernate join the target";
            }
            if (isNamed(mirror, JPA + "JoinTable")) {
                return "it has @JoinTable, which keeps the foreign key on another table";
            }
            if (isNamed(mirror, JPA + "JoinColumn") && value(mirror, "referencedColumnName") instanceof String named
                    && !named.isEmpty()) {
                return "its @JoinColumn names referencedColumnName = \"" + named
                        + "\", which may not be the target's primary key";
            }
            if (isNamed(mirror, JPA + "JoinColumn") && value(mirror, "table") instanceof String table
                    && !table.isEmpty()) {
                return "its @JoinColumn names table = \"" + table + "\", so the foreign key is on another table";
            }
        }
        return null;
    }

    /** {@code @Name} of an annotation, as a message shows it. */
    private static String simple(AnnotationMirror mirror) {
        return "@" + mirror.getAnnotationType().asElement().getSimpleName();
    }

    /**
     * The Hibernate restriction ({@code @SQLRestriction} or {@code @Where}, read by name) on {@code target} or a
     * mapped superclass, as {@code @Name}, or {@code null} when it has none: a read then takes the id from the
     * joined row, which the restriction can empty while the foreign key holds a value ({@code MQ3029}, D-136).
     */
    String restriction(TypeElement target) {
        for (DeclaredType type : hierarchy((DeclaredType) target.asType())) {
            for (AnnotationMirror mirror : type.asElement().getAnnotationMirrors()) {
                if (isNamed(mirror, HIBERNATE + "SQLRestriction") || isNamed(mirror, HIBERNATE + "Where")) {
                    return simple(mirror);
                }
            }
        }
        return null;
    }

    /**
     * Why a to-one to {@code target} can't be read by its id, or {@code null} when the target has one basic
     * {@code @Id}: an {@code @IdClass}, an {@code @EmbeddedId}, several ids, or an id that is itself an association
     * (derived identity, {@code @MapsId}) (R-PROC-33, D-136).
     */
    String idFormRefusal(TypeElement target) {
        List<DeclaredType> hierarchy = hierarchy((DeclaredType) target.asType());
        boolean property = defaultsToProperty(hierarchy, false);
        for (DeclaredType type : hierarchy) {
            if (hasAny(type.asElement(), List.of(ID_CLASS))) {
                return target.getSimpleName() + " has @IdClass, so its id is composite";
            }
        }
        Id id = id(target);
        if (id.attributes().size() != 1) {
            return id.attributes().isEmpty() ? target.getSimpleName() + " has no @Id the processor can see"
                    : target.getSimpleName() + " has a composite id";
        }
        if (!id.components().isEmpty()) {
            return target.getSimpleName() + " has an @EmbeddedId";
        }
        Map<String, EntityAttribute> attributes = attributes(hierarchy, property);
        for (DeclaredType type : hierarchy) {
            for (Element member : type.asElement().getEnclosedElements()) {
                if (hasAny(member, List.of(JPA + "MapsId"))) {
                    return target.getSimpleName() + "'s id is derived from an association (@MapsId)";
                }
            }
        }
        EntityAttribute key = attributes.get(id.attributes().iterator().next());
        if (key == null || key.kind() != EntityAttribute.Kind.BASIC) {
            return target.getSimpleName() + "'s id is "
                    + (key == null ? "not visible to the processor" : "an association or an embedded value");
        }
        return null;
    }

    /**
     * Whether {@code member} carries {@code org.hibernate.annotations.Generated} (by full name, so the JDK's and
     * Jakarta's {@code @Generated} don't count) with {@code writable = false}, an empty {@code sql} and {@code INSERT}
     * among its events: its {@code value} when present and not {@code INSERT} (Hibernate 6), otherwise its
     * {@code event}. A type that doesn't resolve leaves the column written (R-PROC-26, D-125).
     */
    private boolean filledByDatabase(Element member) {
        for (AnnotationMirror mirror : member.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().getKind() != TypeKind.DECLARED || !isNamed(mirror, HIBERNATE_GENERATED)) {
                continue;
            }
            Map<String, Object> values = new HashMap<>();
            elements.getElementValuesWithDefaults(mirror).forEach((key, value) ->
                    values.put(key.getSimpleName().toString(), value.getValue()));
            if (!Boolean.FALSE.equals(values.getOrDefault("writable", Boolean.FALSE))
                    || !"".equals(values.getOrDefault("sql", ""))) {
                return false;
            }
            String time = constant(values.get("value"));
            if (time != null && !time.equals("INSERT")) {
                // Hibernate 6's GenerationTime: only ALWAYS has INSERT among its events.
                return time.equals("ALWAYS");
            }
            // Hibernate 7 has no value; an absent event counts as INSERT, as does Hibernate 6's default value.
            return !(values.get("event") instanceof List<?> events) || events.stream()
                    .anyMatch(event -> "INSERT".equals(constant(((AnnotationValue) event).getValue())));
        }
        return false;
    }

    /** The simple name of an enum constant an annotation value holds, or {@code null} for anything else. */
    private static String constant(Object value) {
        return value instanceof VariableElement element ? element.getSimpleName().toString() : null;
    }

    /** Whether a {@code @Column} or {@code @JoinColumn} on {@code member} sets {@code element} to {@code false}. */
    private static boolean columnSaysFalse(Element member, String element) {
        return member.getAnnotationMirrors().stream()
                .anyMatch(mirror -> COLUMNS.stream().anyMatch(column -> isNamed(mirror, column))
                        && Boolean.FALSE.equals(value(mirror, element)));
    }

    /** {@code type} as the entity an association reaches, or {@code null} when it names no class. */
    private static DeclaredType entity(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && hasAny(((DeclaredType) type).asElement(), MANAGED)
                ? (DeclaredType) type : null;
    }

    /** The property a getter exposes, or {@code null} when {@code method} is not a getter. */
    private static String propertyName(ExecutableElement method) {
        if (!method.getParameters().isEmpty()) {
            return null;
        }
        String name = method.getSimpleName().toString();
        TypeMirror returned = method.getReturnType();
        if (name.length() > 3 && name.startsWith("get") && returned.getKind() != TypeKind.VOID) {
            return decapitalize(name.substring(3));
        }
        boolean flag = returned.getKind() == TypeKind.BOOLEAN || returned.toString().equals("java.lang.Boolean");
        if (name.length() > 2 && name.startsWith("is") && flag) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    /** The JavaBeans property name: the first letter lowered, unless the name starts with two capitals. */
    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Whether {@code @Access} on {@code element} asks for property access; {@code null} when it carries none. */
    private static Boolean propertyAccess(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (isNamed(mirror, ACCESS)) {
                // The value is an enum constant, read as its element; @Access has no other member.
                return mirror.getElementValues().values().stream()
                        .anyMatch(value -> value.getValue() instanceof Element constant
                                && constant.getSimpleName().contentEquals(PROPERTY));
            }
        }
        return null;
    }

    /** Whether {@code element} carries an annotation meta-annotated as a Hibernate id or value generator. */
    private static boolean hasGeneratorAnnotation(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (hasAny(mirror.getAnnotationType().asElement(), GENERATOR_META)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAny(Element element, List<String> annotations) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            for (String annotation : annotations) {
                if (isNamed(mirror, annotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The value written for {@code mirror}'s member {@code name}, or {@code null} when it takes its default. */
    private static Object value(AnnotationMirror mirror, String name) {
        return mirror.getElementValues().entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals(name))
                .map(entry -> entry.getValue().getValue())
                .findFirst().orElse(null);
    }

    private static boolean isNamed(AnnotationMirror mirror, String qualifiedName) {
        return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(qualifiedName);
    }
}
