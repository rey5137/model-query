package com.rey.modelquery.sample.springboot.mysql;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;

/** Recipe 8's second model: the same songs root, a different shape, the same profile enricher fills it. */
@QueryModel(root = SongEntity.class)
public record SongCreditView(@PrimaryKey Long id, String title, Long artistId, Integer catalogId,
        @Transient String profile) {

    SongCreditView withProfile(String value) {
        return new SongCreditView(id, title, artistId, catalogId, value);
    }
}
