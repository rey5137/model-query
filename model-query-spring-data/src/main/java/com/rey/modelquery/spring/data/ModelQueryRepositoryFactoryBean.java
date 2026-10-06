package com.rey.modelquery.spring.data;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.data.repository.core.support.RepositoryFragment;
import org.springframework.data.repository.util.TxUtils;

/**
 * A {@link JpaRepositoryFactoryBean} that adds the {@link ModelQueryRepository} implementation to a repository
 * extending it, set through {@code @EnableJpaRepositories(repositoryFactoryBeanClass = ...)} (R-SPR-02, D-50). The
 * implementation's executor runs on the {@code EntityManager} Spring Data gives this repository, so the vendor profile
 * is resolved per {@code EntityManagerFactory}, with the context's {@code ModelQueryConfig} bean, or
 * {@link com.rey.modelquery.jpa.ModelQueryConfig#defaults()} when there is none, passed through the context's
 * {@link ModelQueryConfigurer} bean when there is one (R-SPR-13). {@code stream}, {@code update} and {@code delete} run in a transaction of the
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
    @SuppressWarnings("unchecked")
    public void afterPropertiesSet() {
        // Without an EntityManager the superclass fails with its own message.
        if (entityManager != null && ModelQueryRepository.class.isAssignableFrom(repositoryInterface)) {
            Class<Object> root = (Class<Object>) ModelQueryRepositoryFragmentFactoryBean.rootEntity(
                    repositoryInterface);
            super.setRepositoryFragments(fragments.append(
                    RepositoryFragment.implemented(ModelQueryRepository.class, fragment(root))));
        }
        super.afterPropertiesSet();
    }

    private <E> ModelQueryRepositoryFragment<E> fragment(Class<E> rootEntity) {
        return ModelQueryRepositoryFragmentFactoryBean.fragment(rootEntity, entityManager, beanFactory,
                transactionManagerName, lazyInit);
    }
}
