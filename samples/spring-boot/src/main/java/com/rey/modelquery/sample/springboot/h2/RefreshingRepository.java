package com.rey.modelquery.sample.springboot.h2;

/**
 * The sample's own repository base interface, implemented by {@link RefreshingJpaRepository} (recipe 1, D-111 item 1).
 */
public interface RefreshingRepository<T, ID> {

    /** Reloads {@code id} from the database and returns it, or {@code null} when it does not exist. */
    T refreshAndGet(ID id);
}
