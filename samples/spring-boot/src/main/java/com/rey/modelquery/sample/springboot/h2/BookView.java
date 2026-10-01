package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** The model read through {@link BookRepository}. */
@QueryModel(root = BookEntity.class)
public record BookView(@PrimaryKey Long id, String title, Integer released) {}
