package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.StampedOrderEntity;
import java.util.Date;

/** A {@code Date} field over a {@code Timestamp} attribute, which takes the built-in converter. */
@QueryModel(root = StampedOrderEntity.class)
public record StampedOrderView(@PrimaryKey Long id, @Column(attribute = "placedAt") Date placed) {}
