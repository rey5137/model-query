package com.rey.modelquery.processor.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

@Entity
@IdClass(StockId.class)
public class StockEntity {

    @Id
    protected Long warehouseId;

    @Id
    protected Long productId;

    protected Integer quantity;
}
