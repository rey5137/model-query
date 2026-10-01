package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.spring.data.ModelQueryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** Both the usual Spring Data methods and model queries; the starter wires the model-query half. */
public interface BookRepository extends JpaRepository<BookEntity, Long>, ModelQueryRepository<BookEntity> {}
