package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.LabelEntity;

/** A nested model with an entity join of its own, read under {@code @Join(entityJoin)} (AC-GEN-47). */
@QueryModel(root = CustomerEntity.class)
@JoinEntity(name = "label", entity = LabelEntity.class, on = @JoinEntity.On(column = "id"))
public record LabelledCustomer(
        @PrimaryKey Long id,
        String name,
        @Column(entityJoin = "label", attribute = "name") String labelName) {}
