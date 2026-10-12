package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

/** Fixture entity over the {@code order_view} view: read-only, but a root like any table (TCK AC-COL-29). */
@Entity
@Immutable
@Table(name = "order_view")
public class ViewOrderEntity {
    @Id
    Long id;

    @Column(name = "customer_id")
    Long customerId;

    String status;

    BigDecimal total;
}
