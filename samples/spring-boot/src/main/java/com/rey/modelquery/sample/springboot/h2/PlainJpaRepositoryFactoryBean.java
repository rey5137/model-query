package com.rey.modelquery.sample.springboot.h2;

import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.Repository;

/**
 * The sample's own factory bean, a plain {@code JpaRepositoryFactoryBean} subclass that does not extend
 * {@code ModelQueryRepositoryFactoryBean}; the starter still adds the model-query fragment to the repositories that
 * declare {@code ModelQueryRepository} (recipe 1, D-113).
 */
public class PlainJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
        extends JpaRepositoryFactoryBean<T, S, I> {

    public PlainJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }
}
