package com.rey.modelquery.jpa;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.function.Function;

/**
 * Runs each chunk of a {@code commitEachChunk()} bulk write in a new transaction: the library never starts a
 * transaction itself (INV-8). A plain-JPA caller usually opens a resource-local {@code EntityManager} on the factory,
 * then {@code begin}, runs the chunk, {@code commit}, and closes it, rolling back when the chunk throws; the Spring
 * starter provides one. The executor passes its own {@code EntityManagerFactory}, so one callback serves every
 * datasource. Set on {@link ModelQueryConfig#chunkTransactions(ChunkTransactions)}.
 *
 * @implSpec R-WRT-19, D-62
 */
@Incubating
public interface ChunkTransactions {

    /**
     * Opens an {@code EntityManager} on {@code emf} in a new transaction, runs {@code chunk} on it, commits, closes
     * it and returns the chunk's result. A chunk's key select and its write both run on that {@code EntityManager}.
     * When {@code chunk} throws, the transaction rolls back and the exception propagates; a commit that fails throws
     * too, and the write then reports the chunk's keys as in doubt (R-WRT-20).
     */
    <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk);

    /**
     * Throws when this callback cannot serve {@code emf}. The executor calls it before the write's first statement,
     * its flush included, and turns what it throws into {@code MQ4004}. Does nothing by default.
     */
    default void checkServes(EntityManagerFactory emf) {}
}
