package com.rey.modelquery.sample.springboot;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Builds the pieces each database's configuration has in common. */
public final class Databases {

    private Databases() {
    }

    /** A plain {@code DriverManager} data source; a real application would use a pool. */
    public static DataSource dataSource(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password);
    }

    /** A Hibernate factory over {@code dataSource} for the entities in {@code entityPackage}, tables created. */
    public static LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource,
            String entityPackage) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(entityPackage);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        return factory;
    }
}
