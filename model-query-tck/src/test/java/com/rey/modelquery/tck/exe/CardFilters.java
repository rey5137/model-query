package com.rey.modelquery.tck.exe;

import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.TaggedCardEntity;

/**
 * A card filtered through its element collections: a basic element, a converted one, an embeddable's attribute, a
 * map's value and an embeddable map value's attribute, each a generated join with its table constant (AC-EXE-30).
 */
@QueryModel(root = TaggedCardEntity.class)
@FilterColumn(name = "TAG", path = "tags", joinType = JoinKind.LEFT)
@FilterColumn(name = "STATE", path = "states", joinType = JoinKind.LEFT)
@FilterColumn(name = "SPOT_CITY", path = "spots.city", joinType = JoinKind.LEFT)
@FilterColumn(name = "LABEL", path = "labels", joinType = JoinKind.LEFT)
@FilterColumn(name = "MAPPED_SPOT_CITY", path = "spotsMap.city", joinType = JoinKind.LEFT)
public record CardFilters(@PrimaryKey Long id, String name) {}
