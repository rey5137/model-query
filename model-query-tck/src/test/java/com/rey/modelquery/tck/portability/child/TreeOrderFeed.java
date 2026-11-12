package com.rey.modelquery.tck.portability.child;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/** A model rooted at {@link TreeOrderSource}, with a child keyed on the source field {@code id}. */
@QueryModel(root = TreeOrderSource.class)
public record TreeOrderFeed(@PrimaryKey Long id, String status,
        @Child(key = "id", foreignKey = "order.id") List<TreeLine> lines) {}
