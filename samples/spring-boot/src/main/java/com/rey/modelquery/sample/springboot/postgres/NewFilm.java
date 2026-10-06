package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.annotations.InsertModel;
import com.rey.modelquery.annotations.PrimaryKey;

/** One row to import into {@code films}; the processor generates {@code QNewFilm} with {@code insert(rows)}. */
@InsertModel(root = FilmEntity.class)
public record NewFilm(@PrimaryKey Long id, String title, Integer released) {}
