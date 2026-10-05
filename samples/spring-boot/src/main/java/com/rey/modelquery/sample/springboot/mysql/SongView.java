package com.rey.modelquery.sample.springboot.mysql;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;

/** The model recipe 8 enriches: the profile is filled from h2, never read from mysql. */
@QueryModel(root = SongEntity.class)
public record SongView(@PrimaryKey Long id, String title, Integer released, Long userId, Integer userTypeId,
        @Transient String profile) {

    SongView withProfile(String value) {
        return new SongView(id, title, released, userId, userTypeId, value);
    }
}
