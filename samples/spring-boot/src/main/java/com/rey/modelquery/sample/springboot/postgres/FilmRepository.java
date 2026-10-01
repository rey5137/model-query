package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.spring.data.ModelQueryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** Both the usual Spring Data methods and model queries; the starter wires the model-query half. */
public interface FilmRepository extends JpaRepository<FilmEntity, Long>, ModelQueryRepository<FilmEntity> {}
