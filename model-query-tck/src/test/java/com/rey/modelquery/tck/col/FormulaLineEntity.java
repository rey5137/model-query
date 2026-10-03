package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JoinFormula;

/**
 * Fixture entity over {@code formula_lines}: its product is joined through a computed key,
 * {@code upper(product_code)}, which matches the product's String {@code @Id} (TCK AC-COL-16).
 */
@Entity
@Table(name = "formula_lines")
public class FormulaLineEntity {
    @Id
    Long id;

    @Column(name = "product_code")
    String productCode;

    int quantity;

    @ManyToOne
    @JoinFormula("upper(product_code)")
    FormulaProductEntity product;
}
