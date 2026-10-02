package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerNoteEntity;

/** A customer note. */
@QueryModel(root = CustomerNoteEntity.class)
public record Note(@PrimaryKey Long id, String body) {}
