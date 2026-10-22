package com.rey.modelquery.tck.flt;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.KeysetTypeEntity;

/** A {@code keyset_types} row, the child of {@link TokenTwins} matched on its token. */
@QueryModel(root = KeysetTypeEntity.class)
public record TokenTwin(@PrimaryKey Long id) {}
