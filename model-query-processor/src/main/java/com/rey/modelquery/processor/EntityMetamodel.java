package com.rey.modelquery.processor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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
    private static final List<String> COLLECTION =
            List.of(JPA + "OneToMany", JPA + "ManyToMany", JPA + "ElementCollection");
    private static final String EMBEDDABLE = JPA + "Embeddable";
    private static final String TRANSIENT = JPA + "Transient";

    private final Types types;

    EntityMetamodel(Types types) {
        this.types = types;
    }

    /**
     * Where a column's attribute path ends, or why it does not resolve.
     *
     * @param attribute the attribute the path ends at, or {@code null} when it does not resolve
     * @param problem what is wrong with the path, worded to follow {@code "Model.field: "}; {@code null} when resolved
     */
    record Resolution(EntityAttribute attribute, String problem) {}

    /**
     * Resolves {@code path} from {@code root}. Every segment but the last must be an embedded value: a column's path
     * never crosses an association (R-PROC-06, D-41).
     *
     * @implSpec R-GEN-03
     */
    Resolution resolve(TypeElement root, String path) {
        String[] segments = path.split("\\.", -1);
        DeclaredType owner = (DeclaredType) root.asType();
        boolean property = false;
        for (int i = 0; ; i++) {
            List<DeclaredType> hierarchy = hierarchy(owner);
            property = defaultsToProperty(hierarchy, property);
            EntityAttribute found = attributes(hierarchy, property).get(segments[i]);
            String ownerName = owner.asElement().getSimpleName().toString();
            if (found == null) {
                return new Resolution(null, "no attribute '" + segments[i] + "' on " + ownerName);
            }
            if (i == segments.length - 1) {
                return new Resolution(found, null);
            }
            switch (found.kind()) {
                case EMBEDDED -> owner = (DeclaredType) found.type();
                case TO_ONE, COLLECTION -> {
                    return new Resolution(null, "'" + segments[i] + "' on " + ownerName + " is an association, so '"
                            + path + "' needs @Join or @FilterColumn");
                }
                default -> {
                    return new Resolution(null, "'" + segments[i] + "' on " + ownerName
                            + " is not an embedded value, so it has no attribute '" + segments[i + 1] + "'");
                }
            }
        }
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
        EntityAttribute.Kind kind;
        if (hasAny(member, TO_ONE)) {
            kind = EntityAttribute.Kind.TO_ONE;
        } else if (hasAny(member, COLLECTION)) {
            kind = EntityAttribute.Kind.COLLECTION;
        } else if (type.getKind() == TypeKind.DECLARED && (hasAny(member, EMBEDDED)
                || hasAny(((DeclaredType) type).asElement(), List.of(EMBEDDABLE)))) {
            kind = EntityAttribute.Kind.EMBEDDED;
        } else {
            kind = EntityAttribute.Kind.BASIC;
        }
        return new EntityAttribute(name, type, kind);
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

    private static boolean isNamed(AnnotationMirror mirror, String qualifiedName) {
        return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(qualifiedName);
    }
}
