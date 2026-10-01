package com.rey.modelquery.sample.springboot.postgres;

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

/** The postgres datasource, its {@code EntityManagerFactory}, transaction manager and repositories. */
@Configuration
@EnableJpaRepositories(basePackageClasses = FilmRepository.class,
        entityManagerFactoryRef = "postgresEntityManagerFactory",
        transactionManagerRef = "postgresTransactionManager")
class FilmDataConfig {

    @Bean
    DataSource postgresDataSource(@Value("${sample.postgres.url}") String url,
            @Value("${sample.postgres.username:}") String username,
            @Value("${sample.postgres.password:}") String password) {
        return Databases.dataSource(url, username, password);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean postgresEntityManagerFactory(DataSource postgresDataSource) {
        return Databases.entityManagerFactory(postgresDataSource, FilmEntity.class.getPackageName());
    }

    @Bean
    PlatformTransactionManager postgresTransactionManager(EntityManagerFactory postgresEntityManagerFactory) {
        return new JpaTransactionManager(postgresEntityManagerFactory);
    }
}
