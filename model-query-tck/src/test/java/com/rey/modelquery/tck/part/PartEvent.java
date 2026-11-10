package com.rey.modelquery.tck.part;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.time.LocalDateTime;

/** An event and when it was created: the partition column, bounded by a key group's range (R-FCH-18). */
@QueryModel(root = PartEventEntity.class)
public record PartEvent(@PrimaryKey Long id, LocalDateTime createdAt) {}
