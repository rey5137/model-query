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
 * @param version whether the attribute is the entity's {@code @Version}
 * @param updatable {@code false} when its {@code @Column} or {@code @JoinColumn} says {@code updatable = false}
 * @param insertable {@code false} when its {@code @Column} or {@code @JoinColumn} says {@code insertable = false}
 * @param mappedBy what {@code @OneToOne(mappedBy)} names on the inverse side of a to-one; {@code null} on the owning
 *     side and for any other attribute
 * @param filledByDatabase whether the attribute carries a non-writable Hibernate {@code @Generated} whose events
 *     include {@code INSERT} (R-PROC-26, D-125)
 * @param idFormRefusal why a to-one's mapping can't be read by its id (R-PROC-33, D-136): the inverse side, a
 *     {@code referencedColumnName}, {@code @JoinColumns}, a formula or {@code @NotFound}; {@code null} when it can
 *     be, and for any other attribute
 * @param mapsId whether the to-one carries {@code @MapsId}: its foreign key is the entity's own id, which an insert
 *     writes through the id column (R-PROC-26)
 * @param nullable whether the attribute's mapping can hold NULL: an {@code @Id}, a primitive, and any attribute whose
 *     {@code @Column(nullable = false)} or {@code @Basic(optional = false)} says it cannot, are non-nullable
 *     (R-SRC-02)
 * @param enumType the constant named by {@code @Enumerated}, {@code ORDINAL} by its own default, or {@code null}
 *     without the annotation; what the database stores, which a branch must share with its shape (R-PROC-38)
 * @param temporal the constant named by {@code @Temporal}, or {@code null} without it; as {@code enumType}, a branch
 *     and its shape must agree on it (R-PROC-38)
 */
record EntityAttribute(
        String name, TypeMirror type, Kind kind, DeclaredType target, boolean version, boolean updatable,
        boolean insertable, String mappedBy, boolean filledByDatabase, String idFormRefusal,
        boolean mapsId, boolean nullable, String enumType, String temporal) {

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
