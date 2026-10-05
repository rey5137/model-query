package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.spring.data.ModelQueryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** The profile repository recipe 8's lookup reads, over h2, while the enriched rows come from mysql. */
public interface ProfileRepository extends JpaRepository<ProfileEntity, Long>, ModelQueryRepository<ProfileEntity> {}
