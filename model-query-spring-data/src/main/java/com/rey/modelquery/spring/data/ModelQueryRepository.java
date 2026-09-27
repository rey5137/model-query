package com.rey.modelquery.spring.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * A {@link JpaRepository} that can also run model queries.
 *
 * @param <E>  the entity type
 * @param <ID> the entity id type
 */
@NoRepositoryBean
public interface ModelQueryRepository<E, ID> extends JpaRepository<E, ID> {
}
