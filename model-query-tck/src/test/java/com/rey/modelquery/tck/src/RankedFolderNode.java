package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.annotations.WindowFunction;

/** Ungrouped windowed source over {@link FolderNode}: inherits uniqueness from walk vs tree (AC-SRC-29, AC-SRC-30). */
@SourceModel(root = FolderNode.class)
public record RankedFolderNode(
        @PrimaryKey Long id,
        Long parentId,
        String name,
        Integer level,
        @Window(function = WindowFunction.ROW_NUMBER, orderBy = "level desc") Long rank) {}
