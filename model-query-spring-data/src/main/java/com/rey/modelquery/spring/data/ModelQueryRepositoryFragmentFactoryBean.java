package com.rey.modelquery.spring.data;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.core.ResolvableType;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.AbstractRepositoryMetadata;
import org.springframework.data.repository.util.TxUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.function.SingletonSupplier;

/**
 * Builds one repository's {@link ModelQueryRepository} fragment for a factory bean that is not a
 * {@link ModelQueryRepositoryFactoryBean}: the starter registers it as the definition's {@code customImplementation} of
 * a {@code JpaRepositoryFactoryBean} subclass whose repository extends {@link ModelQueryRepository}, so the subclass's
 * own class, overrides and {@code repositoryBaseClass} are kept (R-SPR-02, D-113). It takes the definition's
 * {@code entityManager} — the shared {@code EntityManager} of the repository's {@code EntityManagerFactory} — and its
 * {@code transactionManager} and {@code lazyInit} settings, as {@link ModelQueryRepositoryFactoryBean} does, so the
 * vendor configuration and {@code MQ4007} hold alike (R-SPR-13).
 *
 * @param <E> the repository's domain type, the {@code E} of its {@code ModelQueryRepository<E>}
 * @implSpec R-SPR-02, R-SPR-12, R-SPR-13, D-113
 */
@Incubating
public class ModelQueryRepositoryFragmentFactoryBean<E> implements FactoryBean<ModelQueryRepository<E>>,
        BeanFactoryAware {

    private Class<? extends Repository<?, ?>> repositoryInterface;
    private EntityManager entityManager;
    private BeanFactory beanFactory;
    private String transactionManagerName = TxUtils.DEFAULT_TRANSACTION_MANAGER;
    private boolean lazyInit;

    /** The repository whose fragment this builds; its domain type is what the fragment queries. */
    public void setRepositoryInterface(Class<? extends Repository<?, ?>> repositoryInterface) {
        this.repositoryInterface = repositoryInterface;
    }

    /**
     * The definition's {@code entityManager}, the shared {@code EntityManager} of the repository's
     * {@code EntityManagerFactory} (R-SPR-02). The starter sets it as the property beside {@code repositoryInterface};
     * a definition that names none leaves the annotation to inject the context's persistence context, as for any
     * repository.
     */
    @PersistenceContext
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The {@code transactionManagerRef} of the repository's {@code @EnableJpaRepositories} (D-54). */
    public void setTransactionManager(String transactionManager) {
        this.transactionManagerName = transactionManager == null
                ? TxUtils.DEFAULT_TRANSACTION_MANAGER : transactionManager;
    }

    /** The definition's lazy-init setting: a lazy repository resolves its vendor on the first call. */
    public void setLazyInit(boolean lazyInit) {
        this.lazyInit = lazyInit;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ModelQueryRepository<E> getObject() {
        Class<?> root = rootEntity(repositoryInterface);
        return fragment((Class<E>) root, entityManager, beanFactory, transactionManagerName, lazyInit);
    }

    @Override
    public Class<?> getObjectType() {
        return ModelQueryRepository.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /** The domain type of {@code repositoryInterface}, refusing a {@code ModelQueryRepository<E>} of another type. */
    static Class<?> rootEntity(Class<?> repositoryInterface) {
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

    /**
     * Builds the fragment as {@link ModelQueryRepositoryFactoryBean} does, over the {@code EntityManager} the
     * repository is bound to, with the context's {@code ModelQueryConfig} bean (or the defaults) through the context's
     * {@code ModelQueryConfigurer} bean when there is one (R-SPR-13).
     */
    static <E> ModelQueryRepositoryFragment<E> fragment(Class<E> rootEntity, EntityManager entityManager,
            BeanFactory beanFactory, String transactionManagerName, boolean lazyInit) {
        ModelQueryConfig config = beanFactory.getBeanProvider(ModelQueryConfig.class)
                .getIfAvailable(ModelQueryConfig::defaults);
        ModelQueryConfigurer configurer = beanFactory.getBeanProvider(ModelQueryConfigurer.class).getIfAvailable();
        if (configurer != null) {
            config = Objects.requireNonNull(configurer.configure(config, entityManager.getEntityManagerFactory()),
                    () -> "ModelQueryConfigurer " + configurer.getClass().getName() + " returned null for "
                            + rootEntity.getName() + "; return the shared config to keep it");
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
}
