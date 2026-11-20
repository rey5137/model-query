package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import java.util.Map;

/**
 * A card and its map element collections: basic string map, enum-keyed map, embeddable-valued element model map,
 * and holder's map reached through to-one (AC-FCH-39, AC-FCH-40).
 */
@QueryModel(root = TaggedCardEntity.class)
public record CardMaps(
        @PrimaryKey Long id,
        String name,
        @Child(through = "labels") Map<String, String> labels,
        @Child(through = "counts") Map<OrderStatus, Integer> counts,
        @Child(through = "spotsMap") Map<String, SpotRow> spots,
        @Child(through = "holder.labels") Map<String, String> holderLabels) {}
