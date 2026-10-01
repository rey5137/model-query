package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import java.util.Optional;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.SortDirection;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaExpression;

/**
 * Hibernate's {@link ProviderSupport}: the vendor from the dialect, without a connection, and the grouped count with
 * {@code JpaCriteriaQuery#createCountQuery()}, which renders {@code select count(*) from (<grouped query>)}, null
 * precedence with {@code HibernateCriteriaBuilder#sort}, which the dialect renders natively or emulates, and the
 * configured {@code hibernate.order_by.default_null_ordering}. Registered with {@code ServiceLoader}.
 *
 * @implSpec R-VND-04, R-VND-05, R-EXE-03, R-COL-12, R-PAG-05
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
    public Optional<CriteriaQuery<Long>> countQuery(CriteriaQuery<?> groupedQuery) {
        // Another provider's query: the executor counts client-side.
        return groupedQuery instanceof JpaCriteriaQuery<?> grouped
                ? Optional.of(grouped.createCountQuery())
                : Optional.empty();
    }

    @Override
    public Optional<NullPrecedenceRenderer> nullPrecedence() {
        return Optional.of(HibernateProviderSupport::order);
    }

    /**
     * Hibernate's {@code hibernate.order_by.default_null_ordering}, which it applies to every order rendered without a
     * null precedence, in both directions; empty when it is not set, or set to {@code none}.
     */
    @Override
    public Optional<NullPrecedence> defaultNullPrecedence(EntityManagerFactory emf) {
        return sessionFactory(emf).map(sf -> sf.getSessionFactoryOptions().getDefaultNullPrecedence())
                .flatMap(HibernateProviderSupport::precedenceOf);
    }

    /** {@code configured} as a precedence; empty for {@code NONE} or no setting at all. */
    private static Optional<NullPrecedence> precedenceOf(org.hibernate.query.NullPrecedence configured) {
        return switch (configured) {
            case FIRST -> Optional.of(NullPrecedence.FIRST);
            case LAST -> Optional.of(NullPrecedence.LAST);
            case NONE -> Optional.empty();
        };
    }

    /** {@code expression} ordered with {@code precedence}, or empty for another provider's builder. */
    static Optional<Order> order(CriteriaBuilder cb, Expression<?> expression, boolean ascending,
            NullPrecedence precedence) {
        if (!(cb instanceof HibernateCriteriaBuilder hibernate) || !(expression instanceof JpaExpression<?> sorted)) {
            return Optional.empty();
        }
        return Optional.of(hibernate.sort(sorted, ascending ? SortDirection.ASCENDING : SortDirection.DESCENDING,
                switch (precedence) {
                    case DEFAULT -> org.hibernate.query.NullPrecedence.NONE;
                    case FIRST -> org.hibernate.query.NullPrecedence.FIRST;
                    case LAST -> org.hibernate.query.NullPrecedence.LAST;
                }));
    }

    private static Optional<SessionFactoryImplementor> sessionFactory(EntityManagerFactory emf) {
        try {
            return Optional.of(emf.unwrap(SessionFactoryImplementor.class));
        } catch (PersistenceException notHibernate) {
            return Optional.empty();
        }
    }
}
