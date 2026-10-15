package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code folder_nodes}: each row points at its parent, which a recursive source's step turns into
 * the next level (TCK AC-SRC-12 to AC-SRC-14). {@code parent_id} is NULL at a root, and the seed's two-row cycle has
 * none. No column holds the level: the CTE assigns it, and a {@code @Depth} field is read from no attribute
 * (R-PROC-37).
 */
@Entity
@Table(name = "folder_nodes")
public class FolderNodeEntity {
    @Id
    Long id;

    @Column(name = "parent_id")
    Long parentId;

    String name;
}
