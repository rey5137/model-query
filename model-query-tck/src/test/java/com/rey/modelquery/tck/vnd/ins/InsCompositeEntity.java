package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** A root whose composite id generates one part, which no bulk insert writes (R-WRT-26, D-116). */
@Entity
@Table(name = "ins_composite")
@IdClass(InsCompositeEntity.Key.class)
public class InsCompositeEntity {
    @Id
    Long tenant;
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ins_composite_gen")
    @SequenceGenerator(name = "ins_composite_gen", sequenceName = "ins_composite_seq", allocationSize = 1)
    Long serial;
    String name;

    /** The {@link IdClass}. */
    public static class Key implements Serializable {
        Long tenant;
        Long serial;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(tenant, other.tenant)
                    && Objects.equals(serial, other.serial);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenant, serial);
        }
    }
}
