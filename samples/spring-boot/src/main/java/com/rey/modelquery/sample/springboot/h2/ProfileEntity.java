package com.rey.modelquery.sample.springboot.h2;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user profile, keyed by {@code (userId, userTypeId)}. The profiles live on h2 while the rows that reference them
 * live on mysql, so recipe 8's enricher reads across two datasources (D-111 item 8).
 */
@Entity
@Table(name = "profiles")
public class ProfileEntity {

    @Id
    Long id;
    Long userId;
    Integer userTypeId;
    String profile;

    protected ProfileEntity() {
    }

    public ProfileEntity(Long id, Long userId, Integer userTypeId, String profile) {
        this.id = id;
        this.userId = userId;
        this.userTypeId = userTypeId;
        this.profile = profile;
    }
}
