package com.rey.modelquery.tck.sel;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Selected;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * A record model with a {@code @Selected} set, a column that is {@code NULL} on two thirds of the rows, a filter-only
 * column, and the nullable referrer twice: a {@code LEFT} {@code @Join} that misses on those rows, and a to-one
 * {@code @Child} loaded by a fetch plan (D-120).
 */
@QueryModel(root = OrderEntity.class)
@FilterColumn(name = "PLACED", path = "placedAt")
public record SelOrder(
        @PrimaryKey Long id,
        String status,
        BigDecimal total,
        @Column(attribute = "referrerId") Long referrerKey,
        @Join Optional<SelCustomer> referrer,
        @Child(key = "referrerId") Optional<SelCustomer> referrerChild,
        @Selected SelectSet<SelOrder> selected) {

    /** A copy that carries the set over (R-GEN-31). */
    SelOrder withStatus(String value) {
        return new SelOrder(id, value, total, referrerKey, referrer, referrerChild, selected);
    }
}
