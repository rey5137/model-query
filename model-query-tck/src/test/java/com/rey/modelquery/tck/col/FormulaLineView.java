package com.rey.modelquery.tck.col;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.Optional;

/** A line and its product, joined through the Hibernate {@code @JoinFormula} on {@code upper(product_code)}. */
@QueryModel(root = FormulaLineEntity.class)
public record FormulaLineView(@PrimaryKey Long id, Integer quantity, @Join Optional<FormulaProductView> product) {}
