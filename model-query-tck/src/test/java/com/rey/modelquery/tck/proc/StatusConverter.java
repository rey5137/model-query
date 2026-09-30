package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.tck.col.OrderStatus;

/** The order status as the model holds it, an enum, over the text the entity holds. */
public final class StatusConverter implements ColumnConverter<OrderStatus, String> {

    public static final StatusConverter INSTANCE = new StatusConverter();

    private StatusConverter() {}

    @Override
    public OrderStatus toModel(String attribute) {
        return OrderStatus.valueOf(attribute);
    }

    @Override
    public String toAttribute(OrderStatus model) {
        return model.name();
    }
}
