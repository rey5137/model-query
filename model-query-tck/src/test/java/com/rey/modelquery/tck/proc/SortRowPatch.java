package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.NullableSortEntity;

/** An update model over a nullable text column, with the change set the processor generates (api/14 R-WRT-02). */
@QueryModel(root = NullableSortEntity.class, generateChanges = true)
public record SortRowPatch(@PrimaryKey Long id, Integer sortInt, String sortText) {}
