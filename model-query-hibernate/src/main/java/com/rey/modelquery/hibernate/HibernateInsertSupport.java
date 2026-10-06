package com.rey.modelquery.hibernate;

import com.rey.modelquery.jpa.spi.ConflictClause;
import com.rey.modelquery.jpa.spi.IdGeneration;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.InsertTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.Version;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.Generator;
import org.hibernate.id.CompositeNestedGeneratedValueGenerator;
import org.hibernate.id.IdentityGenerator;
import org.hibernate.id.enhanced.SequenceStyleGenerator;
import org.hibernate.id.enhanced.TableGenerator;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.JoinedSubclassEntityPersister;
import org.hibernate.query.MutationQuery;
import org.hibernate.query.criteria.JpaCriteriaInsert;
import org.hibernate.query.criteria.JpaCriteriaInsertSelect;
import org.hibernate.type.AssociationType;
import org.hibernate.type.ForeignKeyDirection;
import org.hibernate.type.Type;

/**
 * Hibernate's {@link InsertSupport}, compiled against Hibernate 6.6 and run on 7.x as well: it uses only API both
 * have, and names a generator class that moved between them by its name (D-117). The generator comes from the
 * entity's persister, and keys are drawn from it as Hibernate draws them for {@code persist} (D-116).
 *
 * @implSpec R-VND-14, D-116, D-117
 */
final class HibernateInsertSupport implements InsertSupport {

    /** The assigned generator: {@code id.Assigned} on Hibernate 6.x, {@code generator.Assigned} on 7. */
    private static final List<String> ASSIGNED = List.of("org.hibernate.id.Assigned",
            "org.hibernate.generator.Assigned");

    /** The UUID generators: the legacy {@code UUIDGenerator} and {@code @UuidGenerator}'s. */
    private static final List<String> UUID = List.of("org.hibernate.id.UUIDGenerator",
            "org.hibernate.id.uuid.UuidGenerator");

    @Override
    public InsertTarget target(EntityManagerFactory emf, Class<?> entity) {
        EntityPersister persister = persister(emf.unwrap(SessionFactoryImplementor.class), entity);
        List<String> unsupported = new ArrayList<>();
        if (persister instanceof JoinedSubclassEntityPersister) {
            unsupported.add("JOINED inheritance");
        } else if (persister instanceof AbstractEntityPersister tables && tables.getTableNames().length > 1) {
            unsupported.add("a @SecondaryTable");
        }
        Generator generator = persister.getGenerator();
        String name = generator.getClass().getName();
        IdGeneration id = idGeneration(generator, name);
        if (id instanceof IdGeneration.Other && mapsId(persister)) {
            unsupported.add("@MapsId");
        }
        if (generator instanceof CompositeNestedGeneratedValueGenerator composite && generatesParts(composite)) {
            unsupported.add("a composite id with generated parts");
        }
        return new InsertTarget(id, unsupported);
    }

    /**
     * Whether the id is derived from a to-one, as {@code @MapsId} derives it: a one-to-one on the primary key (no
     * column of its own, the foreign key from this entity) or a to-one on the id columns. Asked only of an id whose
     * generator is no other kind, since that generator's class is no guide: {@code ForeignGenerator} on Hibernate 6.6,
     * an anonymous class on 7.
     */
    private static boolean mapsId(EntityPersister persister) {
        if (!(persister instanceof AbstractEntityPersister columns)) {
            return false;
        }
        Set<String> ids = new HashSet<>();
        for (String column : persister.getIdentifierColumnNames()) {
            ids.add(unquoted(column));
        }
        Type[] types = persister.getPropertyTypes();
        for (int i = 0; i < types.length; i++) {
            String[] mapped = columns.getPropertyColumnNames(i);
            if (!types[i].isEntityType()) {
                continue;
            }
            boolean onPrimaryKey = mapped.length == 0 && types[i] instanceof AssociationType association
                    && association.getForeignKeyDirection() == ForeignKeyDirection.FROM_PARENT;
            if (onPrimaryKey || mapped.length > 0
                    && Arrays.stream(mapped).allMatch(column -> ids.contains(unquoted(column)))) {
                return true;
            }
        }
        return false;
    }

    private static String unquoted(String column) {
        return column.replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * {@code generator} as the engine sees it; a composite id's own generator counts as assigned. Only
     * {@code SequenceStyleGenerator} itself is a sequence: a subclass's own key code would never run in an
     * insert-select, which reads the sequence inside the statement, so it is {@code Other}, {@code MQ1805} for either
     * insert (D-117).
     */
    static IdGeneration idGeneration(Generator generator, String name) {
        if (ASSIGNED.contains(name) || generator instanceof CompositeNestedGeneratedValueGenerator composite
                && !generatesParts(composite)) {
            return new IdGeneration.Assigned();
        } else if (generator instanceof IdentityGenerator) {
            return new IdGeneration.Identity();
        } else if (generator.getClass() == SequenceStyleGenerator.class) {
            SequenceStyleGenerator sequence = (SequenceStyleGenerator) generator;
            return new IdGeneration.Sequence(sequence.getDatabaseStructure().isPhysicalSequence(),
                    sequence.getOptimizer().getIncrementSize());
        } else if (generator instanceof TableGenerator) {
            return new IdGeneration.Table();
        } else if (UUID.contains(name)) {
            return new IdGeneration.Uuid();
        }
        return new IdGeneration.Other(name);
    }

    /**
     * Whether a composite id generates any of its parts: whether its generator holds a generation plan, read from
     * the field both Hibernate 6.6 and 7 keep them in, since only 7 has an accessor.
     */
    private static boolean generatesParts(CompositeNestedGeneratedValueGenerator composite) {
        return holdsPlans(CompositeNestedGeneratedValueGenerator.class, composite);
    }

    /**
     * Whether {@code generator}'s {@code generationPlans} field, declared by {@code declaring}, holds a plan. Fails
     * closed: a field that is missing, inaccessible, {@code null} or not a collection counts as holding one, so the
     * root is refused as a composite id with generated parts ({@code MQ1805}), never let through (D-117).
     */
    static boolean holdsPlans(Class<?> declaring, Object generator) {
        try {
            Field plans = declaring.getDeclaredField("generationPlans");
            plans.setAccessible(true);
            return !(plans.get(generator) instanceof Collection<?> held) || !held.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Draws each key from the persister's generator, with no owning entity: the sequence, table and UUID generators
     * the engine calls it for read none (D-116).
     */
    @Override
    public List<Object> generateKeys(EntityManager em, Class<?> entity, int count) {
        SharedSessionContractImplementor session = em.unwrap(SharedSessionContractImplementor.class);
        Generator generator = persister(session.getFactory(), entity).getGenerator();
        if (!(generator instanceof BeforeExecutionGenerator before) || generator.generatedOnExecution()) {
            throw new IllegalArgumentException(entity.getName() + "'s id generator "
                    + generator.getClass().getName() + " does not generate keys before the insert");
        }
        List<Object> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(before.generate(session, null, null, EventType.INSERT));
        }
        return keys;
    }

    /**
     * True on Hibernate 7, which renders {@code doNothing} on every dialect; on 6.x only on the PostgreSQL and MySQL
     * dialects, MariaDB's included: 6.6 drops it on a {@code MERGE} dialect and writes a plain insert (D-116).
     */
    @Override
    public boolean doNothingRendered(EntityManagerFactory emf) {
        if (major() >= 7) {
            return true;
        }
        Dialect dialect = emf.unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect();
        return dialect instanceof PostgreSQLDialect || dialect instanceof MySQLDialect;
    }

    /** The {@code @Version} seed, which Hibernate binds once per row (D-116). */
    @Override
    public int providerBindsPerRow(EntityManagerFactory emf, Class<?> entity) {
        return persister(emf.unwrap(SessionFactoryImplementor.class), entity).isVersioned() ? 1 : 0;
    }

    /**
     * A criteria insert-select: each attribute, dotted through embeddables or to a to-one's id, is a target path, and
     * {@code source} the select. Hibernate adds the generated id and the {@code @Version} seed itself (D-116). The
     * criteria is passed as a {@code JpaCriteriaInsert}, the overload both 6.6 and 7 have, and the query Hibernate
     * returns for it is a {@code jakarta.persistence.Query} on both.
     */
    @Override
    public <E> Query insertSelect(EntityManager em, Class<E> entity, List<String> attributes,
            CriteriaQuery<Tuple> source) {
        Session session = em.unwrap(Session.class);
        JpaCriteriaInsertSelect<E> insert = session.getCriteriaBuilder().createCriteriaInsertSelect(entity);
        Root<E> target = insert.getTarget();
        List<Path<?>> paths = new ArrayList<>(attributes.size());
        for (String attribute : attributes) {
            Path<?> path = target;
            for (String segment : attribute.split("\\.")) {
                path = path.get(segment);
            }
            paths.add(path);
        }
        insert.setInsertionTargetPaths(paths);
        insert.select(source);
        MutationQuery query = session.createMutationQuery((JpaCriteriaInsert<E>) insert);
        if (!(query instanceof Query statement)) {
            throw new IllegalStateException("Hibernate " + Version.getVersionString() + " returned a "
                    + query.getClass().getName() + " for an insert-select, which is not a jakarta.persistence.Query");
        }
        return statement;
    }

    @Override
    public <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
            List<List<Object>> rows, Optional<ConflictClause<E>> conflict) {
        throw new UnsupportedOperationException(entity.getSimpleName() + ": insert-values is built in M10.6");
    }

    private static EntityPersister persister(SessionFactoryImplementor factory, Class<?> entity) {
        return factory.getMappingMetamodel().getEntityDescriptor(entity);
    }

    /** The major version of the Hibernate running. */
    private static int major() {
        String version = Version.getVersionString();
        int dot = version.indexOf('.');
        return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
    }
}
