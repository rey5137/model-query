package com.rey.modelquery.tck.col;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** A sku product, its key and name, read through the non-key {@code sku} join (AC-COL-15). */
@QueryModel(root = SkuProductEntity.class)
public record SkuProductView(@PrimaryKey Long id, String sku, String name, BigDecimal price) {}
