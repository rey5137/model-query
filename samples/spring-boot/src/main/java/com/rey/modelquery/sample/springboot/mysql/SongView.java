package com.rey.modelquery.sample.springboot.mysql;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** The model read through {@link SongRepository}. */
@QueryModel(root = SongEntity.class)
public record SongView(@PrimaryKey Long id, String title, Integer released) {}
