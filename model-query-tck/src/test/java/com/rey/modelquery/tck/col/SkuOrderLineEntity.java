package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code sku_order_lines}: its product is joined through the product's unique non-key
 * {@code sku} column, not its surrogate id (TCK AC-COL-15).
 */
@Entity
@Table(name = "sku_order_lines")
public class SkuOrderLineEntity {
    @Id
    Long id;

    int quantity;

    @ManyToOne
    @JoinColumn(name = "product_sku", referencedColumnName = "sku")
    SkuProductEntity product;
}
