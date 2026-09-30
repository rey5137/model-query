package com.rey.modelquery.processor;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * One persistent attribute of an entity, mapped superclass or embeddable, as the processor read it.
 *
 * @param name the attribute name a Criteria path takes
 * @param type the attribute's declared Java type
 * @param kind what a path may do with it
 * @param target the entity an association reaches, a collection's element included; {@code null} for any other
 *     attribute, an {@code @ElementCollection}, and an association whose declared type does not name its target
 */
record EntityAttribute(String name, TypeMirror type, Kind kind, DeclaredType target) {

    /** Whether a join may follow the attribute: a to-one or collection association of a known target. */
    boolean joinable() {
        return target != null;
    }

    /** What an attribute is to a path (R-GEN-02). */
    enum Kind {
        /** A basic value, including an {@code @Id}. */
        BASIC,
        /** An {@code @Embedded} or {@code @EmbeddedId} value, which a dotted column path may walk into. */
        EMBEDDED,
        /** A {@code @ManyToOne} or {@code @OneToOne} association. */
        TO_ONE,
        /** A {@code @OneToMany}, {@code @ManyToMany} or {@code @ElementCollection} attribute. */
        COLLECTION
    }
}
