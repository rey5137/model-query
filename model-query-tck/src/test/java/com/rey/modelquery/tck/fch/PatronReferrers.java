package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.PatronEntity;
import java.util.List;

/** A customer and its referrers, through a unidirectional many-to-many to its own entity (R-FCH-14). */
@QueryModel(root = PatronEntity.class)
public record PatronReferrers(@PrimaryKey Long id, @Child(through = "referrers") List<Referrer> referrers) {}
