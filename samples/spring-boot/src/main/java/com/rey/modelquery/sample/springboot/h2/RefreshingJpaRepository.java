package com.rey.modelquery.sample.springboot.h2;

import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

/** The sample's own repository base class: a {@code SimpleJpaRepository} that adds {@code refreshAndGet} (recipe 1). */
public class RefreshingJpaRepository<T, ID> extends SimpleJpaRepository<T, ID>
        implements RefreshingRepository<T, ID> {

    private final EntityManager entityManager;

    public RefreshingJpaRepository(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityManager = entityManager;
    }

    @Override
    public T refreshAndGet(ID id) {
        T entity = findById(id).orElse(null);
        if (entity != null) {
            entityManager.refresh(entity);
        }
        return entity;
    }
}
