package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.spring.data.ModelQueryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** A repository of the {@code persist} probe root, for the Spring insert tests (AC-SPR-09). */
public interface InsPersistRepository
        extends JpaRepository<InsPersistEntity, Long>, ModelQueryRepository<InsPersistEntity> {}
