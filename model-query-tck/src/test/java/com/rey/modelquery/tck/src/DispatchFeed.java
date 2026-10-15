package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.Discriminator;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.OrderEntity;

/**
 * The shape of the dispatch feed: a live order and a shelved row, told apart by the branch column of the key (spec
 * api/17 R-SRC-05, R-SRC-06). Its own source is the first branch of every operation over it.
 */
@SourceModel(root = OrderEntity.class)
public record DispatchFeed(
        @Discriminator("live") @PrimaryKey String branch,
        @PrimaryKey Long id,
        @Column(attribute = "status") String label) {}
