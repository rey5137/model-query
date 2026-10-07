package com.rey.modelquery.tck.sel;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Selected;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.tck.vnd.ins.InsPersistEntity;

/** What {@code persist} returns: the id, a column the row sets and one the database defaults, and the set. */
@QueryModel(root = InsPersistEntity.class)
public record SelPersistView(@PrimaryKey Long id, String code, String region,
        @Selected SelectSet<SelPersistView> selected) {}
