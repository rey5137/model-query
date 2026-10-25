package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import java.time.LocalDateTime;

/** Joined-entity grouped totals in the shape of RFC 0022 D.2 (AC-PROC-50). */
@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "rep", entity = CustomerEntity.class, type = JoinKind.INNER,
        on = @JoinEntity.On(column = "REFERRER_ID"))
@FilterColumn(name = "REFERRER_ID", path = "referrerId")
@FilterColumn(name = "ID", path = "id")
public record EntityJoinTotals(
        @GroupBy @Column(entityJoin = "rep", attribute = "country") String country,
        @Aggregate(fn = AggregateFunction.COUNT, entityJoin = "rep", attribute = "id") Long reps,
        @Aggregate(fn = AggregateFunction.COUNT, entityJoin = "rep") Long rows,
        @Aggregate(fn = AggregateFunction.MAX, entityJoin = "rep", attribute = "createdAt") LocalDateTime lastHire) {}
