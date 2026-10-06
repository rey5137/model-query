package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.jpa.HibernateHints;
import org.hibernate.query.SortDirection;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaExpression;

/**
 * Hibernate's {@link ProviderSupport}: the vendor from the dialect, without a connection, and the grouped count with
 * {@code JpaCriteriaQuery#createCountQuery()}, which renders {@code select count(*) from (<grouped query>)}, null
 * precedence with {@code HibernateCriteriaBuilder#sort}, which the dialect renders natively or emulates, and the
 * configured {@code hibernate.order_by.default_null_ordering}, a cursor stream with its fetch-size hint, and the
 * tables an entity reads from its persister's query spaces, and bulk inserts through {@link HibernateInsertSupport}.
 * Registered with {@code ServiceLoader}.
 *
 * @implSpec R-VND-04, R-VND-05, R-EXE-03, R-COL-12, R-PAG-05, R-VND-12, R-VND-13, R-VND-14
 */
@Incubating
public final class HibernateProviderSupport implements ProviderSupport {

    /**
     * The tables an entity reads, per factory and entity class (R-VND-13, D-109): walking every entity descriptor is
     * too costly to repeat per bulk-write statement, so the result is cached. Weak on the factory, so a closed one
     * does not stay reachable; the entity classes stay reachable as long as their class loader.
     */
    private static final Map<EntityManagerFactory, Map<Class<?>, Set<String>>> TABLES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** In a holder, as {@link DefaultNullPrecedence} is, so the provider stays loadable without Hibernate. */
    private static final class Inserts {
        static final InsertSupport INSTANCE = new HibernateInsertSupport();
    }

    /**
     * {@code SessionFactoryOptions.getDefaultNullPrecedence()}, whose return type differs between Hibernate 6 and 7.
     * In a holder so the provider stays loadable without Hibernate on the class path (R-VND-04).
     */
    private static final class DefaultNullPrecedence {
        static final Method METHOD;

        static {
            try {
                METHOD = SessionFactoryOptions.class.getMethod("getDefaultNullPrecedence");
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

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
        return sessionFactory(emf).map(sf -> configuredNullPrecedence(sf.getSessionFactoryOptions()))
                .flatMap(HibernateProviderSupport::precedenceOf);
    }

    /**
     * {@code options}' default null precedence, read reflectively: Hibernate 6 returns
     * {@code org.hibernate.query.NullPrecedence}, Hibernate 7 {@code jakarta.persistence.criteria.Nulls}, so a direct
     * call compiled against one fails to link on the other.
     */
    private static Enum<?> configuredNullPrecedence(SessionFactoryOptions options) {
        try {
            return (Enum<?>) DefaultNullPrecedence.METHOD.invoke(options);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read Hibernate's default null precedence", e);
        }
    }

    /** {@code configured} as a precedence; empty for {@code NONE} or no setting at all. Both enums name the same. */
    private static Optional<NullPrecedence> precedenceOf(Enum<?> configured) {
        return switch (configured.name()) {
            case "FIRST" -> Optional.of(NullPrecedence.FIRST);
            case "LAST" -> Optional.of(NullPrecedence.LAST);
            default -> Optional.empty();
        };
    }

    /**
     * {@code query}'s result stream, read by cursor {@code fetchSize} rows at a time through Hibernate's
     * {@code org.hibernate.fetchSize} hint (R-VND-12, D-108).
     */
    @Override
    public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
        return query.setHint(HibernateHints.HINT_FETCH_SIZE, fetchSize).getResultStream();
    }

    /**
     * The query spaces in {@code emf} of {@code entity} and of each entity subtype: every table reading it touches,
     * its joined supertables, secondary tables and subclass tables included, as {@link #normalised} names; empty for a
     * type that is not an entity there (R-VND-13, D-109).
     */
    @Override
    public Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity) {
        return sessionFactory(emf).filter(sf -> sf.getMappingMetamodel().findEntityDescriptor(entity) != null)
                .map(sf -> TABLES.computeIfAbsent(emf, factory -> new ConcurrentHashMap<>())
                        .computeIfAbsent(entity, type -> tablesOf(sf, type)))
                .orElse(Set.of());
    }

    /** The query spaces of {@code entity} and its subtypes in {@code sf}, as {@link #normalised} names. */
    private static Set<String> tablesOf(SessionFactoryImplementor sf, Class<?> entity) {
        SqlStringGenerationContext context = sf.getSqlStringGenerationContext();
        Set<String> tables = new LinkedHashSet<>();
        sf.getMappingMetamodel().forEachEntityDescriptor(persister -> {
            if (entity.isAssignableFrom(persister.getMappedClass())) {
                for (Serializable space : persister.getQuerySpaces()) {
                    normalised(space.toString(), context.getDefaultCatalog(), context.getDefaultSchema())
                            .ifPresent(tables::add);
                }
            }
        });
        return Set.copyOf(tables);
    }

    /**
     * {@code table} with each part unquoted ({@code "}, {@code `} or {@code []}) and, when it names one part, qualified
     * with {@code catalog} and {@code schema} where configured; empty for a sub-select, which names no table.
     */
    static Optional<String> normalised(String table, Identifier catalog, Identifier schema) {
        String trimmed = table.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("(")) {
            return Optional.empty();
        }
        List<String> parts = parts(trimmed);
        if (parts.size() == 1) {
            if (schema != null) {
                parts.add(0, schema.getText());
            }
            if (catalog != null) {
                parts.add(0, catalog.getText());
            }
        }
        return Optional.of(String.join(".", parts));
    }

    /** The dot-separated parts of {@code name}, unquoted; a dot inside quotes does not separate. */
    private static List<String> parts(String name) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        char close = 0;
        for (char c : name.toCharArray()) {
            if (close != 0) {
                if (c == close) {
                    close = 0;
                } else {
                    part.append(c);
                }
            } else if (c == '"' || c == '`') {
                close = c;
            } else if (c == '[') {
                close = ']';
            } else if (c == '.') {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        parts.add(part.toString());
        return parts;
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

    /** {@link HibernateInsertSupport}: the generator from the persister, and the insert statements (R-VND-14). */
    @Override
    public Optional<InsertSupport> inserts() {
        return Optional.of(Inserts.INSTANCE);
    }

    private static Optional<SessionFactoryImplementor> sessionFactory(EntityManagerFactory emf) {
        try {
            return Optional.of(emf.unwrap(SessionFactoryImplementor.class));
        } catch (PersistenceException notHibernate) {
            return Optional.empty();
        }
    }
}
