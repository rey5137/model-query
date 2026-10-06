package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** The model recipe 8's lookup reads, over {@link ProfileEntity}. */
@QueryModel(root = ProfileEntity.class)
public record ProfileView(@PrimaryKey Long id, Long artistId, Integer catalogId, String profile) {}
