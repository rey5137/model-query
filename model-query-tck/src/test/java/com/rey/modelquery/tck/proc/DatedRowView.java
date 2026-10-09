package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.DatedRowEntity;
import java.util.Date;

/** A model mirroring its entity's {@code Date} attributes, which the provider reports as {@code java.sql} types. */
@QueryModel(root = DatedRowEntity.class)
public record DatedRowView(@PrimaryKey Long id, Date bornOn, Date ringsAt, Date loggedAt) {}
