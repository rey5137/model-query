package com.rey.modelquery.sample.plainjpa;

import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * A class model: the processor generates {@code QOrderView} with a column per field, {@code CUSTOMER} for the
 * {@code @Join}, and, for the filter column, {@code ITEM_SKU} and the collection's {@code ITEMS_TABLE}.
 */
@QueryModel(root = OrderEntity.class)
@FilterColumn(name = "ITEM_SKU", path = "items.sku", joinType = JoinKind.LEFT)
public class OrderView {

    @PrimaryKey
    private Long id;
    private String status;
    private BigDecimal total;
    // Empty for a walk-in sale, which has no customer.
    @Join
    private Optional<CustomerView> customer = Optional.empty();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public Optional<CustomerView> getCustomer() {
        return customer;
    }

    public void setCustomer(Optional<CustomerView> customer) {
        this.customer = customer;
    }
}
