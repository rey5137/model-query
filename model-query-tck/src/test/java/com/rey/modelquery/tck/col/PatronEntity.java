package com.rey.modelquery.tck.col;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.List;

/**
 * Fixture entity over {@code customers} again, read-only: each customer's referrers, a unidirectional many-to-many to
 * this entity itself through the {@code orders} table, where an order's {@code referrer_id} referred its
 * {@code customer_id}. A customer whose orders share a referrer reaches it once per order, and one customer referred
 * itself.
 */
@Entity
@Table(name = "customers")
public class PatronEntity {
    @Id
    Long id;

    String name;

    @ManyToMany
    @JoinTable(name = "orders", joinColumns = @JoinColumn(name = "customer_id"),
            inverseJoinColumns = @JoinColumn(name = "referrer_id"))
    List<PatronEntity> referrers;
}
