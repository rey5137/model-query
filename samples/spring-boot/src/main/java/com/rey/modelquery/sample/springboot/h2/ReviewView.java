package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** The model recipe 2's sub-selects read: the reviewed book id and the rating, over {@link ReviewEntity}. */
@QueryModel(root = ReviewEntity.class)
public record ReviewView(@PrimaryKey Long id, Long bookId, Integer rating) {}
