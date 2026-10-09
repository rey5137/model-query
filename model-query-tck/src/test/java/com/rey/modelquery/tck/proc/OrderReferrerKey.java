package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;

/** A query model reading the nullable {@code referrer} to-one through a converter, to its key (D-124). */
@QueryModel(root = OrderEntity.class)
public record OrderReferrerKey(@PrimaryKey Long id,
        @Column(attribute = "referrer", converter = ReferrerIdConverter.class) Long referrerKey) {}
