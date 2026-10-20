package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ConstantRenderer;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import com.rey.modelquery.core.SequenceRenderer;
import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.core.SubqueryRenderer;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.EntityWriteSupport;
import com.rey.modelquery.jpa.spi.ForeignKeyColumn;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.LockSupport;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.ReturningSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Subquery;
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
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.jpa.HibernateHints;
import org.hibernate.metamodel.mapping.EntityAssociationMapping;
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.query.SortDirection;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaSubQuery;
import org.hibernate.query.criteria.JpaExpression;

/**
 * Hibernate's {@link ProviderSupport}: the vendor from the dialect, without a connection, and the grouped count with
 * {@code JpaCriteriaQuery#createCountQuery()}, which renders {@code select count(*) from (<grouped query>)}, null
 * precedence with {@code HibernateCriteriaBuilder#sort}, which the dialect renders natively or emulates, and the
 * configured {@code hibernate.order_by.default_null_ordering}, a cursor stream with its fetch-size hint, and the
 * tables an entity reads from its persister's query spaces, bulk inserts through {@link HibernateInsertSupport}, and
 * entity-mode updates through {@link HibernateEntityWriteSupport}. Registered with {@code ServiceLoader}.
 *
 * @implSpec R-VND-04, R-VND-05, R-EXE-03, R-COL-12, R-PAG-05, R-VND-12, R-VND-13, R-VND-14, R-VND-20, D-119
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

    private static final System.Logger LOG = System.getLogger(HibernateProviderSupport.class.getName());

    /**
     * The treat renderer per factory: its mapping is read once, not per executor (R-VND-26). Weak on the factory,
     * and the renderer keeps no reference to it, so a closed one does not stay reachable.
     */
    private static final Map<EntityManagerFactory, TreatRenderer> TREATS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Set by {@link #offlineProperties} alone: the factory is the portability dry run's, so the treat renderer gives
     * the table's answer alone and skips the statement preflight, never run over an offline boot (R-VND-26, D-163).
     */
    static final String OFFLINE_BOOT = "com.rey.modelquery.hibernate.offline_boot";

    /**
     * The value {@link #offlineProperties} sets {@link #OFFLINE_BOOT} to: compared by identity, so no setting a user
     * writes, {@code "true"} included, skips the preflight (D-163).
     */
    private static final Object OFFLINE_TOKEN = new Object();

    /** The factories already warned about a disabled treated path, so one WARN per factory (R-VND-26). */
    private static final Set<EntityManagerFactory> TREATS_WARNED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** In a holder, as {@link DefaultNullPrecedence} is, so the provider stays loadable without Hibernate. */
    private static final class Inserts {
        static final InsertSupport INSTANCE = new HibernateInsertSupport();
    }

    /** In a holder, as {@link Inserts} is, so the provider stays loadable without Hibernate. */
    private static final class EntityWrites {
        static final EntityWriteSupport INSTANCE = new HibernateEntityWriteSupport();
    }

    /** In a holder, as {@link Inserts} is, so the provider stays loadable without Hibernate. */
    private static final class Locks {
        static final LockSupport INSTANCE = new HibernateLockSupport();
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

    /**
     * {@link ForeignKeyColumn#LOCAL_COLUMN} when {@code toOne} of {@code entity} is a to-one whose foreign key is on the
     * entity's own table, in columns and not a formula, references its target's primary key and has no not-found
     * action, from Hibernate's mapping: then {@code toOne.id} in a bulk statement renders that column with no join,
     * where a {@code referencedColumnName} of another column, a join table, a secondary table, a formula or a
     * not-found action renders a join (the M22.1 spike). {@code UNKNOWN} for a type that is not an entity in
     * {@code emf}, or an attribute that is not a to-one (R-COL-29, D-136).
     */
    @Override
    public ForeignKeyColumn foreignKeyColumn(EntityManagerFactory emf, Class<?> entity, String toOne) {
        Optional<EntityPersister> persister = sessionFactory(emf)
                .map(sf -> sf.getMappingMetamodel().findEntityDescriptor(entity));
        Optional<EntityAssociationMapping> association = persister.map(p -> p.findAttributeMapping(toOne))
                .filter(EntityAssociationMapping.class::isInstance).map(EntityAssociationMapping.class::cast);
        if (association.isEmpty()) {
            return ForeignKeyColumn.UNKNOWN;
        }
        EntityAssociationMapping mapping = association.get();
        ForeignKeyDescriptor key = mapping.getForeignKeyDescriptor();
        boolean[] formula = new boolean[1];
        key.getKeyPart().forEachSelectable((index, selectable) -> formula[0] |= selectable.isFormula());
        boolean local = mapping.getSideNature() == ForeignKeyDescriptor.Nature.KEY && mapping.isReferenceToPrimaryKey()
                && !hasNotFoundAction(mapping) && !formula[0]
                && sameTable(key.getKeyTable(), persister.get().getMappedTableDetails().getTableName());
        return local ? ForeignKeyColumn.LOCAL_COLUMN : ForeignKeyColumn.JOINS;
    }

    /**
     * Whether the mapping of a to-one carries a not-found action, which Hibernate joins whatever the column: asked of
     * {@code ToOneAttributeMapping#hasNotFoundAction}, an internal type that only the mutation adapters may import
     * (R-WRT-50), so by name; {@code true}, the joining answer, when the method is absent or fails.
     */
    private static boolean hasNotFoundAction(EntityAssociationMapping mapping) {
        try {
            return (boolean) mapping.getClass().getMethod("hasNotFoundAction").invoke(mapping);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** Whether two table names name one table, ignoring quoting and case. */
    private static boolean sameTable(String a, String b) {
        return a != null && b != null && unquoted(a).equalsIgnoreCase(unquoted(b));
    }

    private static String unquoted(String name) {
        return name.replaceAll("[\"`\\[\\]]", "");
    }

    /** The query spaces of {@code entity} and its subtypes in {@code sf}, as {@link #normalised} names. */
    static Set<String> tablesOf(SessionFactoryImplementor sf, Class<?> entity) {
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

    /**
     * {@link HibernateEntityWriteSupport}: {@code Hibernate.unproxy}, and the persister's property access, which
     * enhanced dirty tracking sees (R-WRT-42, D-119).
     */
    @Override
    public Optional<EntityWriteSupport> entityWrites() {
        return Optional.of(EntityWrites.INSTANCE);
    }

    /** {@link HibernateLockSupport}: the dialect decides what renders, and {@code followOnLocking=false} (R-VND-15). */
    @Override
    public Optional<LockSupport> locks() {
        return Optional.of(Locks.INSTANCE);
    }

    /** {@link HibernateConstantRenderer}: the attribute's mapping in {@code emf}'s metamodel (R-VND-16). */
    @Override
    public Optional<ConstantRenderer> constants(EntityManagerFactory emf) {
        return sessionFactory(emf).map(HibernateConstantRenderer::new);
    }

    /**
     * {@link HibernateTreatRenderer} on the 6.6.x and 7.4.x lines, outside compliance mode (R-VND-26, AC-VND-16).
     * Empty, so every treated node is {@code MQ4014}, on any other minor line or under
     * {@code hibernate.jpa.compliance.query=true}; a WARN names the reason, once per factory. The renderer is built
     * once per factory and shared.
     */
    @Override
    @Incubating
    public Optional<TreatRenderer> treats(EntityManagerFactory emf) {
        return treats(emf, Version.getVersionString());
    }

    /** {@link #treats(EntityManagerFactory)} for the Hibernate {@code version} given, a test seam. */
    static Optional<TreatRenderer> treats(EntityManagerFactory emf, String version) {
        Optional<SessionFactoryImplementor> factory = sessionFactory(emf);
        if (factory.isEmpty()) {
            return Optional.empty();
        }
        if (!treatsLine(version)) {
            return warn(emf, "Hibernate " + version + " is outside the measured 6.6.x and 7.4.x lines");
        }
        if (factory.get().getSessionFactoryOptions().getJpaCompliance().isJpaQueryComplianceEnabled()) {
            return warn(emf, "hibernate.jpa.compliance.query=true");
        }
        return Optional.of(TREATS.computeIfAbsent(emf, key -> new HibernateTreatRenderer(factory.get())));
    }

    /** Whether {@code version} is on the 6.6.x or 7.4.x line, the CI matrix's minor lines (R-VND-26). */
    static boolean treatsLine(String version) {
        if (version == null) {
            return false;
        }
        String[] parts = version.split("\\.");
        return parts.length > 2 && ("6".equals(parts[0]) && "6".equals(parts[1])
                || "7".equals(parts[0]) && "4".equals(parts[1]));
    }

    /** {@link HibernateSequenceRenderer}: the dialect's own sequence call (R-VND-18). */
    @Override
    public Optional<SequenceRenderer> sequences(EntityManagerFactory emf) {
        return sessionFactory(emf).map(sf -> new HibernateSequenceRenderer(sf.getJdbcServices().getDialect()));
    }

    /** {@link HibernateMutations}: Hibernate's own mutation translation and execution (R-WRT-50, R-VND-17). */
    @Override
    public Optional<ReturningSupport> returning() {
        return Optional.of(HibernateMutations.INSTANCE);
    }

    /** {@link HibernateSourceRenderer}: {@code with(...)} and {@code from(cte)}, identical on both lines (R-SRC-12). */
    @Override
    public Optional<SourceRenderer> sources() {
        return Optional.of(HibernateSourceRenderer.INSTANCE);
    }

    /** Ordered scalar sub-queries through the same Criteria methods on both Hibernate lines (R-VND-25). */
    @Override
    @Incubating
    public Optional<SubqueryRenderer> subqueries() {
        return Optional.of(new SubqueryRenderer() {
            @Override
            @Incubating
            public <C> Expression<C> firstRow(CriteriaBuilder cb, Subquery<C> sub, List<Order> order) {
                JpaSubQuery<C> query = (JpaSubQuery<C>) sub;
                try {
                    query.orderBy(order);
                    return query.fetch(1);
                } catch (IllegalStateException e) {
                    throw new ModelQueryConfigurationException(MqCode.MQ4014,
                            "ordered scalar sub-queries require hibernate.jpa.compliance.query=false", e);
                }
            }
        });
    }

    /**
     * Hibernate's offline boot, for any vendor but {@code OTHER} (R-VND-20, D-133): no JDBC metadata read and no
     * schema export at boot; the dialect resolved from the target's product name, so a unit's explicit
     * {@code hibernate.dialect} is overridden while its {@code hibernate.dialect_resolvers} still apply; no unit
     * connection provider, which would bypass the stub data source; no global temporary tables created or dropped
     * at boot, whose failure H2's strategy swallows; and {@link #OFFLINE_BOOT}, so no treat preflight runs (D-163).
     */
    @Override
    public Optional<Map<String, Object>> offlineProperties(DatabaseVendor vendor) {
        if (vendor == DatabaseVendor.OTHER) {
            return Optional.empty();
        }
        return Optional.of(Map.of(
                AvailableSettings.ALLOW_METADATA_ON_BOOT, "false",
                AvailableSettings.HBM2DDL_AUTO, "none",
                AvailableSettings.DIALECT, "",
                AvailableSettings.CONNECTION_PROVIDER, "",
                // GlobalTemporaryTableStrategy's keys; the type itself is internal (D-132), the keys are settings.
                "hibernate.query.mutation_strategy.global_temporary.create_tables", "false",
                "hibernate.query.mutation_strategy.global_temporary.drop_tables", "false",
                OFFLINE_BOOT, OFFLINE_TOKEN));
    }

    /** The value {@link #OFFLINE_BOOT} holds on the portability dry run's offline boot alone (D-163). */
    static Object offlineToken() {
        return OFFLINE_TOKEN;
    }

    /**
     * The session factory name of {@code emf}, or empty when it has none or is blank. Read from the factory's options,
     * the only way {@link SessionFactory} gives it on 6.6, where it has no {@code getName()}.
     */
    @Override
    public Optional<String> factoryName(EntityManagerFactory emf) {
        try {
            String name = emf.unwrap(SessionFactory.class).getSessionFactoryOptions().getSessionFactoryName();
            return Optional.ofNullable(name).filter(named -> !named.isBlank());
        } catch (PersistenceException notHibernate) {
            return Optional.empty();
        }
    }

    /** {@code emf} as Hibernate's factory, or empty when it is not Hibernate's (R-VND-14, R-WRT-50). */
    static Optional<SessionFactoryImplementor> sessionFactory(EntityManagerFactory emf) {
        try {
            return Optional.of(emf.unwrap(SessionFactoryImplementor.class));
        } catch (PersistenceException notHibernate) {
            return Optional.empty();
        }
    }

    /** Empty, and one WARN naming why a treated path is disabled (R-VND-26). */
    private static Optional<TreatRenderer> warn(EntityManagerFactory emf, String reason) {
        if (TREATS_WARNED.add(emf)) {
            LOG.log(System.Logger.Level.WARNING, "MQ4014: treated paths are disabled: " + reason);
        }
        return Optional.empty();
    }
}
