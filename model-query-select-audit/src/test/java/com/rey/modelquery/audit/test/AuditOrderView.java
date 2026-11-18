package com.rey.modelquery.audit.test;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;
import java.util.Optional;

@QueryModel(root = AuditOrderEntity.class)
public record AuditOrderView(
        @PrimaryKey Long id,
        Long total,
        String notes,
        @Join Optional<AuditCustomerView> customer,
        @Child(key = "id", foreignKey = "orderId") List<AuditLineView> lines) {
    public String computedNotes() {
        return notes;
    }

    public AuditOrderView withNotes(String value) {
        return new AuditOrderView(id, total, value, customer, lines);
    }
}
