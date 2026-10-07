package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A root with an embeddable with a constructor but no no-arg constructor and no setters, which {@code persist} cannot
 * instantiate (MQ1805) and {@code insert} writes; and a primitive attribute, which a null cannot be set to (MQ1308).
 */
@Entity
@Table(name = "ins_ctor_embedded")
public class InsCtorEmbeddedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Embedded
    Spot spot;

    int ranking;

    /** An embeddable written through its constructor alone. */
    @Embeddable
    public static class Spot {
        private final Integer x;

        public Spot(Integer x) {
            this.x = x;
        }

        public Integer getX() {
            return x;
        }
    }
}
