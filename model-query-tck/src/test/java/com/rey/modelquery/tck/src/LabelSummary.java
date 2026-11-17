package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.LabelEntity;

/** A label model read through a nested entity join (processor/31 R-GEN-43, AC-GEN-50). */
@QueryModel(root = LabelEntity.class)
public record LabelSummary(@PrimaryKey Long id, String name) {}
