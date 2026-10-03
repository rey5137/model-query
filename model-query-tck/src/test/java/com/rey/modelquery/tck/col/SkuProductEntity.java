package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Fixture entity over {@code sku_products}: a surrogate key and a unique non-key {@code sku} column another entity
 * joins through with {@code referencedColumnName} (TCK AC-COL-15).
 */
@Entity
@Table(name = "sku_products")
public class SkuProductEntity {
    @Id
    Long id;

    @Column(name = "sku")
    String sku;

    String name;

    BigDecimal price;
}
