package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;

/**
 * Generated entity joins anchored on the joins declared before them: the referrer, the order whose id is the
 * referrer's, and that order's referrer and customer, the last through a to-one path below the join (AC-GEN-47).
 */
@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "referred", entity = CustomerEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "referrerId"))
@JoinEntity(name = "twin", entity = OrderEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "referredId"))
@JoinEntity(name = "twinReferred", entity = CustomerEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "twinReferrerId"))
@JoinEntity(name = "twinCustomer", entity = CustomerEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "TWIN_CUSTOMER_ID"))
@FilterColumn(name = "TWIN_CUSTOMER_ID", path = "customer.id", entityJoin = "twin")
public record ChainedEntityJoinDetail(
        @PrimaryKey Long id,
        Long referrerId,
        @Column(entityJoin = "referred", attribute = "id") Long referredId,
        @Column(entityJoin = "referred", attribute = "name") String referredName,
        @Column(entityJoin = "twin", attribute = "referrerId") Long twinReferrerId,
        @Column(entityJoin = "twinReferred", attribute = "name") String twinReferredName,
        @Column(entityJoin = "twinCustomer", attribute = "name") String twinCustomerName) {}
