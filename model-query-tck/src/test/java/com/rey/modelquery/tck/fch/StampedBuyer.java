package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import java.time.LocalDateTime;

/** A customer and when it was created: a key-grouped child bounded on {@code createdAt} (R-FCH-18). */
@QueryModel(root = CustomerEntity.class)
public record StampedBuyer(@PrimaryKey Long id, LocalDateTime createdAt) {}
