package com.rey.modelquery.tck.col;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** A formula product, its String key and name, read through the {@code @JoinFormula} join (AC-COL-16). */
@QueryModel(root = FormulaProductEntity.class)
public record FormulaProductView(@PrimaryKey String code, String name, BigDecimal price) {}
