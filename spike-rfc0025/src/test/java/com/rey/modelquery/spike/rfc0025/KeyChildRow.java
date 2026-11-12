package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A child row of the key-type cells. */
@QueryModel(root = Entities.KeyChild.class)
public record KeyChildRow(@PrimaryKey Long id, byte[] bytesKey) {}
