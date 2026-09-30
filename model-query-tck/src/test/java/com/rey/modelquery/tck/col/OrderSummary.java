package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Fixture embeddable over columns of {@code orders} that {@link OrderEntity} also maps directly, so a dotted column
 * can be compared with the plain one. Read-only, with a nested embeddable and an association of its own.
 */
@Embeddable
public class OrderSummary {
    @Column(name = "total", insertable = false, updatable = false)
    BigDecimal amount;

    @Embedded
    Placement placement;

    @ManyToOne
    @JoinColumn(name = "referrer_id", insertable = false, updatable = false)
    CustomerEntity referredBy;

    /** The second level of the embedded path. */
    @Embeddable
    public static class Placement {
        @Column(name = "placed_at", insertable = false, updatable = false)
        LocalDateTime at;
    }
}
