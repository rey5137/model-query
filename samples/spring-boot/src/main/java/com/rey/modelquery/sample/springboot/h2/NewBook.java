package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.annotations.InsertModel;
import com.rey.modelquery.annotations.PrimaryKey;

/**
 * One row to insert into {@code books}. The processor generates {@code QNewBook}, with {@code insert(rows)} for the
 * bulk import and {@code persist(row)} for a single create; the assigned {@code id} is the row's key.
 */
@InsertModel(root = BookEntity.class)
public record NewBook(@PrimaryKey Long id, String title, Integer released) {}
