package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.FolderNodeEntity;

/** The child of {@link FolderChildrenRow}: a folder node, matched on its parent's id. */
@QueryModel(root = FolderNodeEntity.class)
public record FolderChild(@PrimaryKey Long id, String name) {}
