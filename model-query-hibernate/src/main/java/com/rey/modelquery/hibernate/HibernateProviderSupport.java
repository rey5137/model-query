package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.Optional;
import java.util.OptionalLong;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.SelectionQuery;

/**
 * Hibernate's {@link ProviderSupport}: the vendor from the dialect, without a connection, and the grouped count with
 * {@code SelectionQuery#getResultCount()}, which renders {@code select count(*) from (<grouped query>)}. Registered
 * with {@code ServiceLoader}.
 *
 * @implSpec R-VND-04, R-VND-05, R-EXE-03
 */
@Incubating
public final class HibernateProviderSupport implements ProviderSupport {

    @Override
    public boolean supports(EntityManagerFactory emf) {
        return sessionFactory(emf).isPresent();
    }

    @Override
    public Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
        return sessionFactory(emf).map(sf -> vendorOf(sf.getJdbcServices().getDialect()));
    }

    /** The vendor of {@code dialect}, most specific class first: MariaDB's dialect extends MySQL's. */
    static DatabaseVendor vendorOf(Dialect dialect) {
        if (dialect instanceof MariaDBDialect) {
            return DatabaseVendor.MARIADB;
        } else if (dialect instanceof MySQLDialect) {
            return DatabaseVendor.MYSQL;
        } else if (dialect instanceof PostgreSQLDialect) {
            return DatabaseVendor.POSTGRESQL;
        } else if (dialect instanceof H2Dialect) {
            return DatabaseVendor.H2;
        } else if (dialect instanceof OracleDialect) {
            return DatabaseVendor.ORACLE;
        } else if (dialect instanceof SQLServerDialect) {
            return DatabaseVendor.SQLSERVER;
        }
        return DatabaseVendor.OTHER;
    }

    @Override
    public OptionalLong countGroups(TypedQuery<?> groupedQuery) {
        SelectionQuery<?> selection;
        try {
            selection = groupedQuery.unwrap(SelectionQuery.class);
        } catch (PersistenceException notHibernate) {
            return OptionalLong.empty(); // another provider: the executor counts client-side
        }
        return OptionalLong.of(selection.getResultCount());
    }

    private static Optional<SessionFactoryImplementor> sessionFactory(EntityManagerFactory emf) {
        try {
            return Optional.of(emf.unwrap(SessionFactoryImplementor.class));
        } catch (PersistenceException notHibernate) {
            return Optional.empty();
        }
    }
}
