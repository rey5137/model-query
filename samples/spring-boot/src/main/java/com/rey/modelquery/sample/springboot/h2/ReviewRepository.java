package com.rey.modelquery.sample.springboot.h2;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A repository that does not extend {@code ModelQueryRepository}: it keeps the sample's own factory bean and base
 * class, and the starter adds no model-query fragment to it. Migrating repositories one at a time starts here
 * (recipe 1, D-113).
 */
public interface ReviewRepository extends JpaRepository<ReviewEntity, Long>,
        RefreshingRepository<ReviewEntity, Long> {}
