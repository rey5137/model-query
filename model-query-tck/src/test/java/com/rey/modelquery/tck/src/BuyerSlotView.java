package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.NullableSortEntity;

@QueryModel(root = NullableSortEntity.class)
public record BuyerSlotView(
        @PrimaryKey Long id,
        Integer sortInt,
        String sortText) {}
