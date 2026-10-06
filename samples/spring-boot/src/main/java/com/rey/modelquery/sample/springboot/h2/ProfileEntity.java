package com.rey.modelquery.sample.springboot.h2;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An artist profile, keyed by {@code (artistId, catalogId)}. The profiles live on h2 while the rows that reference them
 * live on mysql, so recipe 8's enricher reads across two datasources (D-111 item 8).
 */
@Entity
@Table(name = "profiles")
public class ProfileEntity {

    @Id
    Long id;
    Long artistId;
    Integer catalogId;
    String profile;

    protected ProfileEntity() {
    }

    public ProfileEntity(Long id, Long artistId, Integer catalogId, String profile) {
        this.id = id;
        this.artistId = artistId;
        this.catalogId = catalogId;
        this.profile = profile;
    }
}
