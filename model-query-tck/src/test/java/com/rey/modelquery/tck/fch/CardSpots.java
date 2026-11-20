package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import java.util.List;

@QueryModel(root = TaggedCardEntity.class)
public record CardSpots(@PrimaryKey Long id, @Child(through = "spots") List<SpotRow> spots,
        @Child(through = "orderedSpots") List<SpotRow> orderedSpots,
        @Child(through = "holder.spots") List<SpotRow> holderSpots) {}
