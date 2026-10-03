package com.rey.modelquery.tck.col;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.Optional;

/** An order line and its product, joined through the product's unique non-key {@code sku} (AC-COL-15). */
@QueryModel(root = SkuOrderLineEntity.class)
public record SkuLineView(@PrimaryKey Long id, Integer quantity, @Join Optional<SkuProductView> product) {}
