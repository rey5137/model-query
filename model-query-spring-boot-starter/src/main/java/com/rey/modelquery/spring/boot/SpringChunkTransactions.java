package com.rey.modelquery.spring.boot;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ChunkTransactions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The starter's {@link ChunkTransactions}: runs each chunk in a {@code REQUIRES_NEW} transaction of the
 * {@link JpaTransactionManager} bound to the write's {@code EntityManagerFactory}, on that factory's transactional
 * {@code EntityManager}, so {@code commitEachChunk()} works with no configuration and with several datasources. The
 * manager is found once per factory, then cached (R-SPR-11).
 *
 * @implSpec R-SPR-11, R-WRT-19
 */
final class SpringChunkTransactions implements ChunkTransactions {

    /** A factory's transaction manager, as a {@code REQUIRES_NEW} template, and the factory it binds to. */
    private record Target(TransactionTemplate template, EntityManagerFactory boundFactory) {}

    private final ObjectProvider<PlatformTransactionManager> transactionManagers;
    private final Map<EntityManagerFactory, Target> targets = new ConcurrentHashMap<>();

    SpringChunkTransactions(ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this.transactionManagers = transactionManagers;
    }

    @Override
    public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
        Target target = target(emf);
        return target.template().execute(status ->
                chunk.apply(EntityManagerFactoryUtils.getTransactionalEntityManager(target.boundFactory())));
    }

    /**
     * Finds the transaction manager for {@code emf}, which a write then reuses.
     *
     * @throws ModelQueryConfigurationException {@code MQ4004} when no {@code JpaTransactionManager} is bound to
     *     {@code emf}, or more than one is
     */
    @Override
    public void checkServes(EntityManagerFactory emf) {
        target(emf);
    }

    private Target target(EntityManagerFactory emf) {
        return targets.computeIfAbsent(emf, this::find);
    }

    private Target find(EntityManagerFactory emf) {
        Object wanted = nativeOf(emf);
        List<JpaTransactionManager> bound = transactionManagers.orderedStream()
                .filter(JpaTransactionManager.class::isInstance).map(JpaTransactionManager.class::cast)
                .filter(manager -> manager.getEntityManagerFactory() != null
                        && nativeOf(manager.getEntityManagerFactory()) == wanted)
                .toList();
        if (bound.size() != 1) {
            throw new ModelQueryConfigurationException(MqCode.MQ4004, "commitEachChunk() runs each chunk in a new "
                    + "transaction of the JpaTransactionManager bound to the write's EntityManagerFactory, and "
                    + (bound.isEmpty() ? "none is" : bound.size() + " are") + " bound to " + emf
                    + "; define exactly one, or a ChunkTransactions bean of your own");
        }
        JpaTransactionManager manager = bound.get(0);
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new Target(template, manager.getEntityManagerFactory());
    }

    /** The provider's own factory behind Spring's proxy, so a proxy and its target match. */
    private static Object nativeOf(EntityManagerFactory emf) {
        return emf instanceof EntityManagerFactoryInfo info ? info.getNativeEntityManagerFactory() : emf;
    }
}
