package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.annotations.InsertModel;

/** The row of {@link InsGeneratedUuidEntity}: the label alone, since the id is generated. */
@InsertModel(root = InsGeneratedUuidEntity.class)
public record InsGeneratedUuidRow(String label) {}
