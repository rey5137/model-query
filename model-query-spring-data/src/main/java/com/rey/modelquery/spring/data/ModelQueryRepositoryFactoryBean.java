package com.rey.modelquery.spring.data;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.core.ResolvableType;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.AbstractRepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.data.repository.core.support.RepositoryFragment;
import org.springframework.data.repository.util.TxUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.function.SingletonSupplier;

/**
 * A {@link JpaRepositoryFactoryBean} that adds the {@link ModelQueryRepository} implementation to a repository
 * extending it, set through {@code @EnableJpaRepositories(repositoryFactoryBeanClass = ...)} (R-SPR-02, D-50). The
 * implementation's executor runs on the {@code EntityManager} Spring Data gives this repository, so the vendor profile
 * is resolved per {@code EntityManagerFactory}, with the context's {@code ModelQueryConfig} bean, or
 * {@link ModelQueryConfig#defaults()} when there is none, passed through the context's {@link ModelQueryConfigurer}
 * bean when there is one (R-SPR-13). {@code stream}, {@code update} and {@code delete} run in a transaction of the
 * {@code transactionManagerRef} of the repository's {@code @EnableJpaRepositories} (R-SPR-03, R-SPR-10, D-54).
 *
 * @param <T>  the repository type
 * @param <S>  the repository's domain type
 * @param <ID> the domain type's id type
 * @implSpec R-SPR-02, R-SPR-03, R-SPR-10, R-SPR-12, R-SPR-13
 */
@Incubating
public class ModelQueryRepositoryFactoryBean<T extends Repository<S, ID>, S, ID>
        extends JpaRepositoryFactoryBean<T, S, ID> {

    private final Class<? extends T> repositoryInterface;
    private RepositoryFragments fragments = RepositoryFragments.empty();
    private String transactionManagerName = TxUtils.DEFAULT_TRANSACTION_MANAGER;
    private EntityManager entityManager;
    private BeanFactory beanFactory;
    private boolean lazyInit;

    /** A factory bean for {@code repositoryInterface}, as the repository registrar creates it. */
    public ModelQueryRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
        this.repositoryInterface = repositoryInterface;
    }

    @Override
    @PersistenceContext
    public void setEntityManager(EntityManager entityManager) {
        super.setEntityManager(entityManager);
        this.entityManager = entityManager;
    }

    /** Keeps the repository's own fragments, such as its custom implementation, ahead of the model-query one. */
    @Override
    public void setRepositoryFragments(RepositoryFragments repositoryFragments) {
        super.setRepositoryFragments(repositoryFragments);
        this.fragments = repositoryFragments;
    }

    @Override
    public void setTransactionManager(String transactionManager) {
        super.setTransactionManager(transactionManager);
        this.transactionManagerName = transactionManager == null
                ? TxUtils.DEFAULT_TRANSACTION_MANAGER : transactionManager;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        super.setBeanFactory(beanFactory);
        this.beanFactory = beanFactory;
    }

    @Override
    public void setLazyInit(boolean lazy) {
        super.setLazyInit(lazy);
        this.lazyInit = lazy;
    }

    @Override
    public void afterPropertiesSet() {
        // Without an EntityManager the superclass fails with its own message.
        if (entityManager != null && ModelQueryRepository.class.isAssignableFrom(repositoryInterface)) {
            super.setRepositoryFragments(fragments.append(
                    RepositoryFragment.implemented(ModelQueryRepository.class, fragment(rootEntity()))));
        }
        super.afterPropertiesSet();
    }

    private <E> ModelQueryRepositoryFragment<E> fragment(Class<E> rootEntity) {
        ModelQueryConfig config = beanFactory.getBeanProvider(ModelQueryConfig.class)
                .getIfAvailable(ModelQueryConfig::defaults);
        ModelQueryConfigurer configurer = beanFactory.getBeanProvider(ModelQueryConfigurer.class).getIfAvailable();
        if (configurer != null) {
            config = Objects.requireNonNull(configurer.configure(config, entityManager.getEntityManagerFactory()),
                    () -> "ModelQueryConfigurer " + configurer.getClass().getName() + " returned null for "
                            + repositoryInterface.getName() + "; return the shared config to keep it");
        }
        ModelQueryConfig resolved = config;
        String name = transactionManagerName;
        Supplier<ModelQueryExecutor<E>> executor =
                SingletonSupplier.of(() -> ModelQueryExecutor.create(entityManager, rootEntity, resolved));
        if (!lazyInit) {
            // Resolves the vendor now, as the repository itself is created now; a lazy repository (bootstrap mode
            // LAZY or DEFERRED) leaves it to the first call, so its EntityManagerFactory is not waited on here.
            executor.get();
        }
        return new ModelQueryRepositoryFragment<>(executor,
                () -> BeanFactoryAnnotationUtils.qualifiedBeanOfType(beanFactory, PlatformTransactionManager.class,
                        name));
    }

    /**
     * The repository's domain type, which the {@code E} of {@code ModelQueryRepository<E>} must be, unless it is raw.
     *
     * @throws ModelQueryConfigurationException {@code MQ4007} when {@code E} is another type (R-SPR-12)
     */
    private Class<?> rootEntity() {
        Class<?> domainType = AbstractRepositoryMetadata.getMetadata(repositoryInterface).getDomainType();
        Class<?> declared = ResolvableType.forClass(repositoryInterface).as(ModelQueryRepository.class)
                .resolveGeneric(0);
        if (declared != null && declared != domainType) {
            throw new ModelQueryConfigurationException(MqCode.MQ4007, repositoryInterface.getName()
                    + " declares ModelQueryRepository<" + declared.getName() + "> on a repository of "
                    + domainType.getName() + "; a repository queries its own domain type, so declare "
                    + "ModelQueryRepository<" + domainType.getSimpleName() + "> or give the queries a repository of "
                    + declared.getSimpleName());
        }
        return domainType;
    }
}
