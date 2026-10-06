package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.spring.data.ModelQueryRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** A repository of the UUID-generated probe root, for the Spring insert tests (AC-SPR-09). */
public interface InsUuidRepository extends JpaRepository<InsUuidEntity, UUID>, ModelQueryRepository<InsUuidEntity> {}
