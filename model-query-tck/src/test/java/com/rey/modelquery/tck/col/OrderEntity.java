package com.rey.modelquery.tck.col;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Fixture entity over {@code orders}. */
@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id
    Long id;

    String status;

    BigDecimal total;

    @Column(name = "placed_at")
    LocalDateTime placedAt;

    // The same column read through a converter (R-COL-08).
    @Convert(converter = OrderStatus.TextConverter.class)
    @Column(name = "status", insertable = false, updatable = false)
    OrderStatus statusCode;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    CustomerEntity customer;

    /** Nullable: an INNER join to it removes the orders that have none. */
    @ManyToOne
    @JoinColumn(name = "referrer_id")
    CustomerEntity referrer;

    // The referrer's key as a plain, nullable value: NULL on a row that a join to the order still matches.
    @Column(name = "referrer_id", insertable = false, updatable = false)
    Long referrerId;

    // Bulk updates increment it (api/14 R-WRT-16); the seed leaves it at its default, 0.
    @Version
    Integer version;

    @OneToMany(mappedBy = "order")
    List<OrderItemEntity> items;

    // The inverse side of LabelEntity.orders, for a child loaded through it (R-FCH-14).
    @ManyToMany(mappedBy = "orders")
    List<LabelEntity> labels;

    // A collection of basic values, for resolving paths only: no table is behind it, so it is never queried.
    @ElementCollection
    @CollectionTable(name = "order_tags", joinColumns = @JoinColumn(name = "order_id"))
    @Column(name = "tag")
    List<String> tags;

    // Columns mapped above, read again through embedded values (R-COL-08, D-41).
    @Embedded
    OrderSummary summary;
}
