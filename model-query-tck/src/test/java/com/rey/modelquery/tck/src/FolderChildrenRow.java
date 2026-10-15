package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/**
 * The outer model over the recursive {@link FolderNode} with a {@code @Child}: a fetch plan over it addresses the rows
 * by key, which a recursive source has none unique (spec api/17 R-SRC-09).
 */
@QueryModel(root = FolderNode.class)
public record FolderChildrenRow(@PrimaryKey Long id, String name,
        @Child(key = "id", foreignKey = "parentId") List<FolderChild> children) {}
