package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;
import java.util.List;

/** An order and its items: a child of {@link CustomerOrders} and a parent of {@link Line}. */
@QueryModel(root = OrderEntity.class)
@FilterColumn(name = "ITEM_CODE", path = "items.productCode", joinType = JoinKind.INNER)
public record OrderLines(@PrimaryKey Long id, String status, BigDecimal total,
        @Child(foreignKey = "order.id") List<Line> items, @Transient String profile) {

    OrderLines withProfile(String value) {
        return new OrderLines(id, status, total, items, value);
    }
}
