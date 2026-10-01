package com.rey.modelquery.sample.springboot.mysql;

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

/** The mysql datasource, its {@code EntityManagerFactory}, transaction manager and repositories. */
@Configuration
@EnableJpaRepositories(basePackageClasses = SongRepository.class, entityManagerFactoryRef = "mysqlEntityManagerFactory",
        transactionManagerRef = "mysqlTransactionManager")
class SongDataConfig {

    @Bean
    DataSource mysqlDataSource(@Value("${sample.mysql.url}") String url,
            @Value("${sample.mysql.username:}") String username,
            @Value("${sample.mysql.password:}") String password) {
        return Databases.dataSource(url, username, password);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean mysqlEntityManagerFactory(DataSource mysqlDataSource) {
        return Databases.entityManagerFactory(mysqlDataSource, SongEntity.class.getPackageName());
    }

    @Bean
    PlatformTransactionManager mysqlTransactionManager(EntityManagerFactory mysqlEntityManagerFactory) {
        return new JpaTransactionManager(mysqlEntityManagerFactory);
    }
}
