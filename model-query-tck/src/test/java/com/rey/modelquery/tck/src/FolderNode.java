package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Depth;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.FolderNodeEntity;

/**
 * A recursive source over {@code folder_nodes}: the seed is the row its {@code where} keeps, and every step reads the
 * children of a row and adds one to its level (spec api/17 R-SRC-07, R-PROC-37; RFC 0013 §5).
 */
@SourceModel(root = FolderNodeEntity.class)
public record FolderNode(@PrimaryKey Long id, Long parentId, String name, @Depth Integer level) {}
