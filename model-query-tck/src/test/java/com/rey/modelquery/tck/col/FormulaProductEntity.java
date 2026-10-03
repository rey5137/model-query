package com.rey.modelquery.tck.col;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Fixture entity over {@code formula_products}: a String key a Hibernate {@code @JoinFormula} joins to (AC-COL-16). */
@Entity
@Table(name = "formula_products")
public class FormulaProductEntity {
    @Id
    String code;

    String name;

    BigDecimal price;
}
