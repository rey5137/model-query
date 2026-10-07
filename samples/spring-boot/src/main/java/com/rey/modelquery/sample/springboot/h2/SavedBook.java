package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.time.Instant;

/** What {@code POST /books} answers with: the book as persisted, with the {@code updatedAt} its write stamped. */
@QueryModel(root = BookEntity.class)
public record SavedBook(@PrimaryKey Long id, String title, Integer released, Instant updatedAt) {}
