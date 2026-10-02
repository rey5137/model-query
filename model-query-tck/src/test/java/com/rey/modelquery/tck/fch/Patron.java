package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.tck.col.CustomerEntity;
import java.util.List;

/** A customer, its orders and a tag an enricher fills: on its own and joined to {@link OrderPatrons}. */
@QueryModel(root = CustomerEntity.class)
public record Patron(@PrimaryKey Long id, String name,
        @Child(foreignKey = "customer.id") List<OrderRef> orders,
        @Transient String tag) {

    Patron withTag(String value) {
        return new Patron(id, name, orders, value);
    }
}
