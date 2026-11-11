package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.LabelEntity;

/** A nested model whose second entity join reads its first, read under {@code @Join} (AC-GEN-48). */
@QueryModel(root = CustomerEntity.class)
@JoinEntity(name = "label", entity = LabelEntity.class, on = @JoinEntity.On(column = "id"))
@JoinEntity(name = "labelled", entity = CustomerEntity.class, on = @JoinEntity.On(column = "labelId"))
public record ChainedCustomer(
        @PrimaryKey Long id,
        @Column(entityJoin = "label", attribute = "id") Long labelId,
        @Column(entityJoin = "labelled", attribute = "name") String labelledName) {}
