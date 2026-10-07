package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A {@code persist} returning model's to-one, read by its id alone (R-WRT-48). */
@QueryModel(root = InsSourceEntity.class)
public record InsSourceRef(@PrimaryKey Long id) {}
