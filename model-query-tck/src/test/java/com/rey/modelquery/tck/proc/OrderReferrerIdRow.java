package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A query model that reads the nullable {@code referrer} to-one by its id (D-136) and, with a change set and inserts,
 * writes it by id (R-PROC-26, R-GEN-19): {@code customerId} is the {@code NOT NULL} foreign key an insert needs.
 */
@QueryModel(root = OrderEntity.class, generateChanges = true, generateInserts = true)
public record OrderReferrerIdRow(@PrimaryKey Long id, String status, BigDecimal total, LocalDateTime placedAt,
        @Column(attribute = "customer") Long customerId, @Column(attribute = "referrer") Long referrerId) {}
