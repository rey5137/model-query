package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.jpa.WriteAssignment;
import com.rey.modelquery.jpa.WriteKind;
import com.rey.modelquery.sample.springboot.Databases;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The h2 datasource, its {@code EntityManagerFactory}, transaction manager and repositories. It keeps the sample's own
 * factory bean and repository base class, and the starter still adds the model-query fragment to the repositories that
 * declare {@code ModelQueryRepository} (recipe 1, D-113).
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = BookRepository.class, entityManagerFactoryRef = "h2EntityManagerFactory",
        transactionManagerRef = "h2TransactionManager",
        repositoryFactoryBeanClass = PlainJpaRepositoryFactoryBean.class,
        repositoryBaseClass = RefreshingJpaRepository.class)
class BookDataConfig {

    @Bean
    DataSource h2DataSource(@Value("${sample.h2.url}") String url,
            @Value("${sample.h2.username:}") String username,
            @Value("${sample.h2.password:}") String password) {
        return Databases.dataSource(url, username, password);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean h2EntityManagerFactory(DataSource h2DataSource) {
        return Databases.entityManagerFactory(h2DataSource, BookEntity.class.getPackageName());
    }

    /**
     * Stamps {@code updatedAt} on every model-query insert, {@code persist} and update of a book, so no endpoint sets
     * it by hand. The starter hands every {@code WriteAssignment} bean to its config (R-SPR-13, R-WRT-49).
     */
    @Bean
    WriteAssignment bookUpdatedAt() {
        return WriteAssignment.of(BookEntity.class, "updatedAt", Instant.class, WriteKind.INSERT_AND_UPDATE,
                Instant::now);
    }

    @Bean
    PlatformTransactionManager h2TransactionManager(EntityManagerFactory h2EntityManagerFactory) {
        return new JpaTransactionManager(h2EntityManagerFactory);
    }
}
