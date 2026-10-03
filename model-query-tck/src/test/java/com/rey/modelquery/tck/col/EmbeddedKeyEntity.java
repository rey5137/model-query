package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/** Fixture entity over {@code embedded_key_items}: an {@code @EmbeddedId} of a String and an int (TCK AC-PAG-25). */
@Entity
@Table(name = "embedded_key_items")
public class EmbeddedKeyEntity {
    @EmbeddedId
    Key key;

    String label;

    BigDecimal amount;

    /** The {@link EmbeddedId}: a region code and a sequence number. */
    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "region_code")
        String regionCode;

        @Column(name = "seq_no")
        int seqNo;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(regionCode, other.regionCode) && seqNo == other.seqNo;
        }

        @Override
        public int hashCode() {
            return Objects.hash(regionCode, seqNo);
        }
    }
}
