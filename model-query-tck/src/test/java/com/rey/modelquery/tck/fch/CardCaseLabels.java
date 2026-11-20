package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import java.util.Map;

/** Fixture model for AC-FCH-41 / MQ2609: two entries with one Java key for one parent. */
@QueryModel(root = TaggedCardEntity.class)
public record CardCaseLabels(
        @PrimaryKey Long id,
        @Child(through = "caseLabels") Map<String, String> caseLabels) {}
