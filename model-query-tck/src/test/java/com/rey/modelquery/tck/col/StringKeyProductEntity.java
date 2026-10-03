package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Fixture entity over {@code string_key_products}: a {@code String} {@code @Id} whose codes sort differently from
 * their insertion order, with a shared prefix and mixed case (TCK AC-PAG-24).
 */
@Entity
@Table(name = "string_key_products")
public class StringKeyProductEntity {
    @Id
    String code;

    String name;

    String category;

    BigDecimal price;
}
