package com.rey.modelquery.processor;

import javax.lang.model.type.TypeMirror;

/**
 * One persistent attribute of an entity, mapped superclass or embeddable, as the processor read it.
 *
 * @param name the attribute name a Criteria path takes
 * @param type the attribute's declared Java type
 * @param kind what a path may do with it
 */
record EntityAttribute(String name, TypeMirror type, Kind kind) {

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
