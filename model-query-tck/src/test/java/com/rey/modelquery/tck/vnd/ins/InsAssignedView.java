package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/**
 * One model that reads and creates an {@link InsAssignedEntity} row (R-PROC-26): its inserts write the assigned id,
 * the code and the name, and leave the {@code @Version} to the provider.
 */
@QueryModel(root = InsAssignedEntity.class, generateInserts = true)
public record InsAssignedView(@PrimaryKey Long id, String code, String name, Integer version) {}
