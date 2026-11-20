package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import java.util.List;
import java.util.Set;

/**
 * A card and the values of its element collections: its tags as a list and as a set, its converted states, its
 * scores in their {@code @OrderColumn} order, and its holder's tags, reached through a to-one association (AC-FCH-30
 * to AC-FCH-32).
 */
@QueryModel(root = TaggedCardEntity.class)
public record CardValues(
        @PrimaryKey Long id,
        String name,
        @Child(through = "tags") List<String> tags,
        @Child(through = "tags") Set<String> tagSet,
        @Child(through = "states") List<ConstantStatus> states,
        @Child(through = "scores") List<Integer> scores,
        @Child(through = "holder.tags") List<String> holderTags) {}
