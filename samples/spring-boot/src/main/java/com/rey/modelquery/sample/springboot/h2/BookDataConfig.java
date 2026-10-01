package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.sample.springboot.Databases;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/** The h2 datasource, its {@code EntityManagerFactory}, transaction manager and repositories. */
@Configuration
@EnableJpaRepositories(basePackageClasses = BookRepository.class, entityManagerFactoryRef = "h2EntityManagerFactory",
        transactionManagerRef = "h2TransactionManager")
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

    @Bean
    PlatformTransactionManager h2TransactionManager(EntityManagerFactory h2EntityManagerFactory) {
        return new JpaTransactionManager(h2EntityManagerFactory);
    }
}
