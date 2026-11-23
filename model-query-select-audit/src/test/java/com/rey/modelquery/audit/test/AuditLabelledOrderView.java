package com.rey.modelquery.audit.test;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.Map;

@QueryModel(root = AuditOrderEntity.class)
public record AuditLabelledOrderView(@PrimaryKey Long id, @Child(through = "labels") Map<String, String> labels) {}
