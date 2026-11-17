package com.rey.modelquery.audit.test;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.Join;
import java.util.Optional;
import org.example.audit.bound.CustomerLabel;
import com.rey.modelquery.annotations.QueryModel;

@QueryModel(root = AuditCustomerEntity.class, prefix = "Projection")
public record AuditCustomerView(@PrimaryKey Long id, String name, @Join Optional<CustomerLabel> colleague) {}
