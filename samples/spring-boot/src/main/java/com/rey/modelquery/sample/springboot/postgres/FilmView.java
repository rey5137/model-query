package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** The model read through {@link FilmRepository}. */
@QueryModel(root = FilmEntity.class)
public record FilmView(@PrimaryKey Long id, String title, Integer released) {}
