package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import java.util.List;

/** Fixture model testing that {@code @Child(through = "heldMap") List<HeldCard>} resolves over a Map association. */
@QueryModel(root = TaggedCardEntity.class)
public record CardHeldList(
        @PrimaryKey Long id,
        @Child(through = "heldMap") List<HeldCard> held) {}
