package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/**
 * A query model with a change set that reads the nullable {@code referrer} to-one as the whole entity (D-124): the
 * change set leaves it out, and two thirds of the orders have no referrer.
 */
@QueryModel(root = OrderEntity.class, generateChanges = true)
public record OrderReferrerPatch(@PrimaryKey Long id, String status, BigDecimal total,
        @Column(attribute = "referrer") CustomerEntity referrer) {}
