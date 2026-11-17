package com.rey.modelquery.audit.test;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

@QueryModel(root = AuditLineEntity.class)
public record AuditLineView(@PrimaryKey Long id, Long orderId, String sku) {}
