package com.rey.modelquery.hibernate;

import com.rey.modelquery.jpa.spi.ConflictClause;
import com.rey.modelquery.jpa.spi.IdGeneration;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.InsertTarget;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Query;
import jakarta.persistence.Table;
import jakarta.persistence.Tuple;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.invoke.MethodType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.hibernate.metamodel.mapping.NaturalIdMapping;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.JoinedSubclassEntityPersister;
import org.hibernate.query.MutationQuery;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaConflictClause;
import org.hibernate.query.criteria.JpaConflictUpdateAction;
import org.hibernate.query.criteria.JpaCriteriaInsert;
import org.hibernate.query.criteria.JpaCriteriaInsertSelect;
import org.hibernate.query.criteria.JpaCriteriaInsertValues;
import org.hibernate.query.criteria.JpaValues;
import org.hibernate.query.sqm.tree.domain.SqmPath;
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

    /** The prefix of the parameter names an insert-values binds its values to. */
    private static final String VALUE = "mqValue";

    /** The prefix of the parameter names a conflict update binds its assigned values to. */
    private static final String ASSIGNED_VALUE = "mqAssigned";

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
     * The id, the {@code @NaturalId}, each {@code @Column(unique = true)} or {@code @JoinColumn(unique = true)}
     * attribute, and each {@code @Table(uniqueConstraints)} constraint of the entity or a superclass entity, the ones
     * Hibernate maps (a {@code @MappedSuperclass}'s {@code @Table} is not), whose columns are all attributes' columns.
     * A constraint's column names are compared unquoted and ignoring case with the persister's column names and with
     * each single-column attribute's logical name, which Hibernate resolves them as: its {@code @Column} or
     * {@code @JoinColumn} name, else a basic attribute's own name, so a physical naming strategy that renames columns
     * still matches. A composite id or natural id counts by its attributes and by their leaf paths through
     * embeddables. A unique key only a migration declares, or one {@code orm.xml} declares, is not seen (R-WRT-34).
     */
    @Override
    public List<Set<String>> uniqueKeys(EntityManagerFactory emf, Class<?> entity) {
        EntityPersister persister = persister(emf.unwrap(SessionFactoryImplementor.class), entity);
        EntityType<?> type = emf.getMetamodel().entity(entity);
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        Map<String, Member> members = new LinkedHashMap<>();
        Map<String, String> logical = new LinkedHashMap<>();
        leaves(type, "", persister, columns, members, logical);
        Set<String> leaves = columns.keySet();
        List<Set<String>> keys = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (type.hasSingleIdAttribute()) {
            type.getSingularAttributes().stream().filter(SingularAttribute::isId)
                    .forEach(attribute -> ids.add(attribute.getName()));
        } else {
            type.getIdClassAttributes().forEach(attribute -> ids.add(attribute.getName()));
        }
        addWithLeaves(keys, ids, leaves);
        NaturalIdMapping naturalId = persister.getNaturalIdMapping();
        if (naturalId != null) {
            Set<String> names = new HashSet<>();
            naturalId.getNaturalIdAttributes().forEach(attribute -> names.add(attribute.getAttributeName()));
            addWithLeaves(keys, names, leaves);
        }
        members.forEach((path, member) -> {
            if (member instanceof AnnotatedElement annotated && unique(annotated)) {
                keys.add(Set.of(path));
            }
        });
        Set<Class<?>> entities = new HashSet<>();
        emf.getMetamodel().getEntities().forEach(mapped -> entities.add(mapped.getJavaType()));
        for (Class<?> declaring = entity; declaring != null; declaring = declaring.getSuperclass()) {
            Table table = declaring.getAnnotation(Table.class);
            if (table == null || !entities.contains(declaring)) {
                continue;
            }
            for (UniqueConstraint constraint : table.uniqueConstraints()) {
                Set<String> named = new HashSet<>();
                Arrays.stream(constraint.columnNames()).forEach(column -> named.add(unquoted(column)));
                Set<String> key = new HashSet<>();
                Set<String> covered = new HashSet<>();
                columns.forEach((path, mapped) -> {
                    if (!mapped.isEmpty() && named.containsAll(mapped)) {
                        key.add(path);
                        covered.addAll(mapped);
                    } else if (named.contains(logical.get(path))) {
                        key.add(path);
                        covered.add(logical.get(path));
                    }
                });
                if (covered.equals(named)) {
                    keys.add(Set.copyOf(key));
                }
            }
        }
        return List.copyOf(keys);
    }

    /** Whether {@code member} is annotated {@code @Column(unique = true)} or {@code @JoinColumn(unique = true)}. */
    private static boolean unique(AnnotatedElement member) {
        Column column = member.getAnnotation(Column.class);
        JoinColumn join = member.getAnnotation(JoinColumn.class);
        return column != null && column.unique() || join != null && join.unique();
    }

    /**
     * Records each singular attribute of {@code type} below {@code prefix} that is no embeddable, by its path dotted
     * through embeddables: its columns, unquoted and lower case, where the persister reports them, its Java member,
     * and its logical column name when it has one column.
     */
    private static void leaves(ManagedType<?> type, String prefix, EntityPersister persister,
            Map<String, Set<String>> columns, Map<String, Member> members, Map<String, String> logical) {
        for (SingularAttribute<?, ?> attribute : type.getSingularAttributes()) {
            String path = prefix + attribute.getName();
            if (attribute.getType() instanceof EmbeddableType<?> embeddable) {
                leaves(embeddable, path + ".", persister, columns, members, logical);
                continue;
            }
            members.put(path, attribute.getJavaMember());
            Set<String> mapped = new HashSet<>();
            if (attribute.isId() && prefix.isEmpty()) {
                Arrays.stream(persister.getIdentifierColumnNames()).forEach(column -> mapped.add(unquoted(column)));
            } else if (persister instanceof AbstractEntityPersister mapping) {
                try {
                    Arrays.stream(mapping.getPropertyColumnNames(path)).forEach(column -> mapped.add(unquoted(column)));
                } catch (RuntimeException e) {
                    // A path the persister does not map to columns of its own, which no constraint can name.
                }
            }
            columns.put(path, mapped);
            if (mapped.size() == 1) {
                logicalName(attribute).ifPresent(name -> logical.put(path, name));
            }
        }
    }

    /**
     * {@code attribute}'s logical column name, which a {@code @UniqueConstraint} names it by before any physical
     * naming strategy: its {@code @Column} or {@code @JoinColumn} name, else a basic attribute's own name; unquoted
     * and lower case. Empty for an association with no {@code @JoinColumn} name.
     */
    private static Optional<String> logicalName(SingularAttribute<?, ?> attribute) {
        if (attribute.getJavaMember() instanceof AnnotatedElement annotated) {
            Column column = annotated.getAnnotation(Column.class);
            if (column != null && !column.name().isBlank()) {
                return Optional.of(unquoted(column.name()));
            }
            JoinColumn join = annotated.getAnnotation(JoinColumn.class);
            if (join != null && !join.name().isBlank()) {
                return Optional.of(unquoted(join.name()));
            }
        }
        return attribute.isAssociation() ? Optional.empty() : Optional.of(unquoted(attribute.getName()));
    }

    /**
     * Adds {@code names} as a key, and also their leaf paths when an embeddable among them expands to some, so the
     * key matches however a model names its columns.
     */
    private static void addWithLeaves(List<Set<String>> keys, Set<String> names, Set<String> leaves) {
        keys.add(Set.copyOf(names));
        Set<String> expanded = new HashSet<>();
        for (String name : names) {
            leaves.stream().filter(leaf -> leaf.equals(name) || leaf.startsWith(name + ".")).forEach(expanded::add);
        }
        if (!expanded.isEmpty() && !expanded.equals(names)) {
            keys.add(Set.copyOf(expanded));
        }
    }

    /**
     * A criteria insert-select: each attribute, dotted through embeddables or to a to-one's id, is a target path, and
     * {@code source} the select, whose constants are bound here as their attribute's mapped type, so a converted or
     * enumerated value is written through its mapping (R-WRT-30). Hibernate adds the generated id and the
     * {@code @Version} seed itself (D-116).
     */
    @Override
    public <E> Query insertSelect(EntityManager em, Class<E> entity, List<String> attributes,
            CriteriaQuery<Tuple> source, Map<String, Object> constants) {
        Session session = em.unwrap(Session.class);
        JpaCriteriaInsertSelect<E> insert = session.getCriteriaBuilder().createCriteriaInsertSelect(entity);
        List<Path<?>> paths = paths(insert.getTarget(), attributes);
        if (constants.size() > paths.size()) {
            throw new IllegalArgumentException(entity.getSimpleName() + ": " + constants.size() + " constants for "
                    + paths.size() + " attributes");
        }
        insert.setInsertionTargetPaths(paths);
        insert.select(source);
        Query statement = query(session, insert, "an insert-select");
        // The constants are written to the last attributes, in order. Each binds as its attribute's mapped type: a
        // target path's type, as insertValues binds, is resolved against the source select's FROM, not the target.
        EntityPersister persister = persister(em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class),
                entity);
        int at = paths.size() - constants.size();
        for (Map.Entry<String, Object> constant : constants.entrySet()) {
            TypedBinding.bind(statement, constant.getKey(), constant.getValue(),
                    persister.findByPath(attributes.get(at++)).getSingleJdbcMapping());
        }
        return statement;
    }

    /**
     * A criteria insert of {@code rows} as one multi-row {@code VALUES} statement: each attribute, dotted as for
     * {@link #insertSelect}, is a target path, and each value a named parameter bound here as the path's type, so a
     * {@code null}, an {@code @Enumerated} value or one a JPA {@code AttributeConverter} writes binds through the
     * attribute's mapping, and no value is inlined (R-WRT-14, R-WRT-30). Hibernate adds the {@code @Version} seed to
     * each row itself (D-116). A conflict clause names its key as target paths, and its update's values are named
     * parameters bound likewise (R-WRT-34, R-WRT-35).
     */
    @Override
    public <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
            List<List<Object>> rows, Optional<ConflictClause<E>> conflict) {
        Session session = em.unwrap(Session.class);
        HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
        JpaCriteriaInsertValues<E> insert = cb.createCriteriaInsertValues(entity);
        List<Path<?>> paths = paths(insert.getTarget(), attributes);
        insert.setInsertionTargetPaths(paths);
        var values = new ArrayList<JpaValues>(rows.size());
        var types = new ArrayList<Object>(paths.size());
        for (Path<?> path : paths) {
            types.add(TypedBinding.typeOf(path));
        }
        var bound = new ArrayList<Object>(rows.size() * paths.size());
        for (List<Object> row : rows) {
            if (row.size() != paths.size()) {
                throw new IllegalArgumentException(entity.getSimpleName() + ": a row of " + row.size()
                        + " values for " + paths.size() + " attributes");
            }
            var parameters = new ArrayList<Expression<?>>(row.size());
            for (int c = 0; c < row.size(); c++) {
                parameters.add(cb.parameter(boxed(paths.get(c).getJavaType()), VALUE + bound.size()));
                bound.add(row.get(c));
            }
            values.add(cb.values(parameters));
        }
        insert.values(values);
        var assigned = new ArrayList<Assigned>();
        conflict.ifPresent(clause -> onConflict(insert, clause, cb, assigned));
        Query statement = query(session, insert, "an insert-values");
        assigned.forEach(value -> TypedBinding.bind(statement, value.name(), value.value(), value.type()));
        // Every row has a value per path, so value p is path p % paths' value.
        for (int p = 0; p < bound.size(); p++) {
            TypedBinding.bind(statement, VALUE + p, bound.get(p), types.get(p % types.size()));
        }
        return statement;
    }

    /**
     * Adds {@code clause} to {@code insert}: the key as target paths, then {@code doNothing}, or the update's
     * assignments and {@code where} on the conflict action. A value assigned is a named parameter, added to
     * {@code assigned} to be bound as its target path's type, as the rows' values are.
     */
    private static <E> void onConflict(JpaCriteriaInsertValues<E> insert, ConflictClause<E> clause,
            HibernateCriteriaBuilder cb, List<Assigned> assigned) {
        JpaConflictClause<E> conflict = insert.onConflict();
        conflict.conflictOnConstraintPaths(paths(insert.getTarget(), clause.keyAttributes()));
        if (clause.doNothing()) {
            conflict.onConflictDoNothing();
            return;
        }
        JpaConflictUpdateAction<E> action = conflict.onConflictDoUpdate();
        clause.update(insert.getTarget(), conflict.getExcludedRoot(), cb, new ConflictClause.Assignments() {
            @Override
            public void value(Path<?> target, Object value) {
                String name = ASSIGNED_VALUE + assigned.size();
                assigned.add(new Assigned(name, value, TypedBinding.typeOf(target)));
                setExpression(action, target, cb.parameter(boxed(target.getJavaType()), name));
            }

            @Override
            public void expression(Path<?> target, Expression<?> value) {
                setExpression(action, target, value);
            }
        }).ifPresent(action::where);
    }

    /** A value a conflict update assigns: its parameter's name and the Hibernate type it binds as. */
    private record Assigned(String name, Object value, Object type) {}

    @SuppressWarnings("unchecked")
    private static <Y> void setExpression(JpaConflictUpdateAction<?> action, Path<Y> target, Expression<?> value) {
        action.set(target, (Expression<? extends Y>) value);
    }

    /**
     * Binds a parameter as a target path's or an attribute's Hibernate type, which Hibernate does not infer for an
     * insert's values when the value is bound: it would resolve the type from the value's class, which fails for a
     * converted class and binds an enum by ordinal. The path's node type and the {@code setParameter} overload taking
     * it differ between Hibernate 6.6 ({@code org.hibernate.query.BindableType}) and 7
     * ({@code jakarta.persistence.metamodel.Type}), so both are found by name, once, the first time an insert runs; an
     * attribute's {@code BasicType} is both.
     */
    private static final class TypedBinding {

        private static final Method NODE_TYPE;
        private static final Method SET_PARAMETER;

        static {
            try {
                NODE_TYPE = SqmPath.class.getMethod("getNodeType");
            } catch (NoSuchMethodException e) {
                throw unsupported(e);
            }
            SET_PARAMETER = Arrays.stream(MutationQuery.class.getMethods())
                    .filter(m -> m.getName().equals("setParameter") && m.getParameterCount() == 3)
                    .filter(m -> m.getParameterTypes()[0] == String.class && m.getParameterTypes()[1] == Object.class
                            && m.getParameterTypes()[2] != Class.class
                            && m.getParameterTypes()[2].isAssignableFrom(NODE_TYPE.getReturnType()))
                    .findFirst().orElseThrow(() -> unsupported(null));
        }

        private TypedBinding() {}

        /** {@code path}'s Hibernate type, which {@link #bind} takes. */
        static Object typeOf(Path<?> path) {
            try {
                return NODE_TYPE.invoke(path);
            } catch (ReflectiveOperationException e) {
                throw unsupported(e);
            }
        }

        /**
         * Binds {@code value} to the parameter {@code name} of {@code statement} as {@code type}, a path's node type or
         * an attribute's {@code BasicType}.
         */
        static void bind(Query statement, String name, Object value, Object type) {
            try {
                SET_PARAMETER.invoke(statement.unwrap(MutationQuery.class), name, value, type);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw unsupported(e);
            } catch (IllegalAccessException e) {
                throw unsupported(e);
            }
        }

        private static IllegalStateException unsupported(Exception cause) {
            return new IllegalStateException("Hibernate " + Version.getVersionString() + " has no SqmPath.getNodeType()"
                    + " and setParameter(String, Object, type) taking it, which an insert binds its values with",
                    cause);
        }
    }

    /** The target path of each attribute, dotted through embeddables or to a to-one's id. */
    private static List<Path<?>> paths(Root<?> target, List<String> attributes) {
        List<Path<?>> paths = new ArrayList<>(attributes.size());
        for (String attribute : attributes) {
            Path<?> path = target;
            for (String segment : attribute.split("\\.")) {
                path = path.get(segment);
            }
            paths.add(path);
        }
        return paths;
    }

    /**
     * The mutation query Hibernate creates for {@code insert}, passed as a {@code JpaCriteriaInsert}, the overload
     * both 6.6 and 7 have; the query it returns is a {@code jakarta.persistence.Query} on both.
     */
    private static <E> Query query(Session session, JpaCriteriaInsert<E> insert, String what) {
        MutationQuery query = session.createMutationQuery(insert);
        if (!(query instanceof Query statement)) {
            throw new IllegalStateException("Hibernate " + Version.getVersionString() + " returned a "
                    + query.getClass().getName() + " for " + what + ", which is not a jakarta.persistence.Query");
        }
        return statement;
    }

    /** {@code type}, or its wrapper class for a primitive, which a parameter takes. */
    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
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
