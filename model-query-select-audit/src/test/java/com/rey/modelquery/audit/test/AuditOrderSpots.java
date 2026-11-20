package com.rey.modelquery.audit.test;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

@QueryModel(root = AuditOrderEntity.class)
public record AuditOrderSpots(@PrimaryKey Long id, @Child(through = "spots") List<AuditSpotRow> spots) {}
