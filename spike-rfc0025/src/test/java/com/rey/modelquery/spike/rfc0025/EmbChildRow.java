package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A child of {@link EmbParentRow}; its embedded parent key is a selected column for the hand-written child. */
@QueryModel(root = Entities.EmbChild.class)
public record EmbChildRow(@PrimaryKey Long id, @Column(attribute = "parent") Entities.EmbKey parent) {}
