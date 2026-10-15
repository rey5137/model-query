package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.Discriminator;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.NullableSortEntity;

/**
 * The shelved branch of {@link DispatchFeed}: the shape's fields read from {@code nullable_sort_rows}, whose label
 * column is of the shape's type and stores other values (R-PROC-38, R-SRC-06).
 */
@SourceModel(root = NullableSortEntity.class, shape = DispatchFeed.class)
public record ShelvedDispatchFeed(
        @Discriminator("shelved") @PrimaryKey String branch,
        @PrimaryKey Long id,
        @Column(attribute = "sortText") String label) {}
