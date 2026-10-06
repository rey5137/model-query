package com.rey.modelquery.sample.springboot.mysql;

/** Recipe 8's profile key: the {@code (artistId, catalogId)} pair a song carries (D-111 item 8). */
public record ArtistRef(long artistId, int catalogId) {

    /** The ref the pair names, or {@code null} when either half is missing, so the enricher skips the row. */
    static ArtistRef of(Long artistId, Integer catalogId) {
        return artistId == null || catalogId == null ? null : new ArtistRef(artistId, catalogId);
    }
}
