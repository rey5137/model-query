package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** The model read through {@link FilmRepository}; recipe 4 orders it by an expression over {@code tickets}. */
@QueryModel(root = FilmEntity.class)
public record FilmView(@PrimaryKey Long id, String title, Integer released, String genre, Long tickets) {}
