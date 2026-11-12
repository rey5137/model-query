package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;

/** A plain source over orders. */
@SourceModel(root = Entities.SpOrder.class)
public record OrderSrc(@PrimaryKey Long id, String status) {}
