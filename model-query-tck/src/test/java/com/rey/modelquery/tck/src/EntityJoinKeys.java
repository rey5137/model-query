package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.OrderEntity;

/** The source anchors for the generated entity-join query (AC-SRC-53). */
@SourceModel(root = OrderEntity.class)
public record EntityJoinKeys(@PrimaryKey Long id, @Column(attribute = "customer") Long customerId) {}
