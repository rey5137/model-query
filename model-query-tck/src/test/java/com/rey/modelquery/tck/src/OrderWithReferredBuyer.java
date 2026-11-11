package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/** The referrer read through an entity join, its source and label through the nested model's own joins (AC-GEN-49). */
@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "referred", entity = CustomerEntity.class, on = @JoinEntity.On(column = "referrerId"))
public record OrderWithReferredBuyer(
        @PrimaryKey Long id,
        Long referrerId,
        @Join(entityJoin = "referred") Optional<JoinBuyer> referred) {}
