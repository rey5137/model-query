package com.rey.modelquery.hibernate;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ExpectedNarrowing;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.core.TreatUse;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SecondaryTables;
import jakarta.persistence.criteria.CollectionJoin;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.MapJoin;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.SetJoin;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hibernate.Session;
import org.hibernate.Version;
import org.hibernate.annotations.DiscriminatorOptions;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.JoinedSubclassEntityPersister;
import org.hibernate.persister.entity.SingleTableEntityPersister;
import org.hibernate.persister.entity.UnionSubclassEntityPersister;
import org.hibernate.query.spi.QueryImplementor;
import org.hibernate.query.spi.SqmQuery;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.type.Type;

/**
 * Hibernate's {@link TreatRenderer} (R-VND-26): {@code treat} through {@code CriteriaBuilder#treat}, and
 * {@code refusal} from the cells RFC 0016's spike measured on 6.6 and 7.4, all three Tier-1 vendors and every
 * strategy, keyed by shape, anchoring, use set, strategy, mapping variant and, on {@code SINGLE_TABLE}, whether the
 * attribute's column is shared (AC-COL-51). The mapping gates of AC-VND-18 refuse a whole hierarchy. Anything the
 * table doesn't name is refused. {@code preflight} translates a select statement to Hibernate's SQL AST through the
 * line's adapter ({@link PreflightTranslation}), with no JDBC call, and refuses it when {@link TreatPreflight} finds a
 * node it reads untreated narrowed, a sub-select root narrowed that the engine did not declare, or a shape it does not
 * recognise. It never widens the table: {@code refusal} runs
 * first, and the preflight after it.
 *
 * <p>The mapping is read from the entity persisters once, when the renderer is built; it is immutable and holds no
 * reference to the factory, so one instance serves every thread and is cached per factory. On {@code SINGLE_TABLE} a
 * dotted path's column is its first segment's, and a join or path through a shared column is refused. Nothing a
 * preflight computes is kept: each call translates and walks its statement again.
 *
 * @implSpec R-VND-26, AC-VND-17, AC-VND-18, AC-VND-21, AC-COL-51
 */
final class HibernateTreatRenderer implements TreatRenderer {

    /** The anchored {@code CONTENT} cells: {@code READ} rows of {@code rfc/0016-spike-data/renderer-allowlist.tsv}. */
    private static final Set<Set<TreatUse>> CONTENT = cells(predicates(), List.of(
            "SELECT", "ORDER", "SELECT+EXPRESSION", "ORDER+EXPRESSION", "PREDICATE+EXPRESSION",
            "SELECT+LEFT_JOIN", "ORDER+LEFT_JOIN", "PREDICATE+LEFT_JOIN", "NULL_TEST+LEFT_JOIN",
            "NULL_TEST+NEGATED+LEFT_JOIN", "PREDICATE+NULL_TEST+DISJUNCTION+LEFT_JOIN",
            "SELECT+INNER_JOIN", "ORDER+INNER_JOIN", "PREDICATE+INNER_JOIN", "NULL_TEST+INNER_JOIN",
            "NULL_TEST+NEGATED+INNER_JOIN", "PREDICATE+NULL_TEST+DISJUNCTION+INNER_JOIN",
            // A correlated count's column over its own treated root (R-COL-31). The spike's LEFT-joined count read
            // its target from an untreated sub-select root, which the engine reports unanchored; an anchored site
            // with LEFT_JOIN treats twice, which nobody ran, so that set is refused (D-156).
            "SELECT+AGGREGATE+SUBQUERY"));

    /**
     * The anchored {@code COUNT} cells: the spike's count rows, the three null-accepting sets measured as
     * {@code count(root.id)} ({@code S2_FRESH_COUNT_ID}), and a treated join a count keeps ({@code COUNT_DERIVED}).
     */
    private static final Set<Set<TreatUse>> COUNT = cells(predicates(), List.of(
            "PREDICATE+LEFT_JOIN", "NULL_TEST+LEFT_JOIN", "NULL_TEST+NEGATED+LEFT_JOIN",
            "PREDICATE+NULL_TEST+DISJUNCTION+LEFT_JOIN",
            "PREDICATE+INNER_JOIN", "NULL_TEST+INNER_JOIN", "NULL_TEST+NEGATED+INNER_JOIN",
            "PREDICATE+NULL_TEST+DISJUNCTION+INNER_JOIN"));

    /** A treated join's own site in a count, with no attribute ({@code COUNT_DERIVED FROM_*_JOIN}). */
    private static final Set<Set<TreatUse>> COUNT_JOINS = cells(List.of(), List.of("INNER_JOIN", "LEFT_JOIN"));

    /**
     * The {@code TYPE} cells of a content statement: the {@code READ} rows the spike's table marks {@code ALLOW}, the
     * order only where the persister maps a discriminator, and a type predicate under an {@code or}, measured in the
     * {@code COMBINED_TYPE_TREAT/OR} rows, which RFC 0016 §10 admits per site (D-158).
     */
    private static final Set<Set<TreatUse>> TYPE_CONTENT = cells(List.of(), List.of("TYPE+SELECT", "TYPE+ORDER",
            "TYPE+PREDICATE", "TYPE+PREDICATE+DISJUNCTION",
            "TYPE+PREDICATE+NEGATED+NULL_TEST+DISJUNCTION+LEFT_JOIN"));

    /** The {@code TYPE} cells of a count: its {@code COUNT} rows marked {@code ALLOW}, and the {@code or} above. */
    private static final Set<Set<TreatUse>> TYPE_COUNT = cells(List.of(), List.of("TYPE+PREDICATE",
            "TYPE+PREDICATE+DISJUNCTION", "TYPE+PREDICATE+NEGATED+NULL_TEST+DISJUNCTION+LEFT_JOIN"));

    /** The {@code TYPE} cells of a grouped statement: a type group key, selected or not ({@code GROUP_BY+TYPE}). */
    private static final Set<Set<TreatUse>> TYPE_GROUPED = cells(List.of(), List.of("TYPE+GROUP_BY",
            "TYPE+SELECT+GROUP_BY"));

    /** The null-accepting count sets, measured as a root-id count on the core mappings only. */
    private static final Set<Set<TreatUse>> ROOT_ID_COUNTED =
            cells(List.of(), List.of("NULL_TEST", "NULL_TEST+NEGATED", "PREDICATE+NULL_TEST+DISJUNCTION"));

    /**
     * The shared-column {@code CONTENT} cells: the shared probes' select, order and predicate rows (AC-COL-51). None
     * with {@code SUBQUERY}: a path {@code exists} reads only columns on its path, an outer one inside it is
     * {@code MQ1302}, and a path through a shared column is refused, so no TCK case reaches them (D-156).
     */
    private static final Set<Set<TreatUse>> SHARED_CONTENT = cells(predicates(), List.of("SELECT", "ORDER",
            "SELECT+EXPRESSION")).stream().filter(cell -> !cell.contains(TreatUse.SUBQUERY))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * The shared-column {@code COUNT} cells: only the {@code or} contexts that passed on the spike's count path (a
     * {@code DISJUNCTION}), less the null-accepting {@code ne} set a standalone predicate also reports; no root-id
     * count was measured on a shared column (RFC 0016 §10). The {@code exists} sets are refused, as in content.
     */
    private static final Set<Set<TreatUse>> SHARED_COUNT = minus(cells(predicates(), List.of()).stream()
            .filter(cell -> cell.contains(TreatUse.DISJUNCTION) && !cell.contains(TreatUse.SUBQUERY))
            .collect(Collectors.toUnmodifiableSet()), ROOT_ID_COUNTED);

    /** The count cells a mapping variant measured: its runs had no root-id count; its content cells are the core's. */
    private static final Set<Set<TreatUse>> VARIANT_COUNT = minus(COUNT, ROOT_ID_COUNTED);

    /** The preflight's line adapters, the newest first: at most one probes true on a Hibernate line (R-VND-26). */
    private static final List<PreflightTranslation> LINES = List.of(new Hibernate7Preflight(),
            new Hibernate6Preflight());

    private static final String IMPLICIT_FOR_JOINED = "hibernate.discriminator.implicit_for_joined";

    private static final String IGNORE_EXPLICIT_FOR_JOINED = "hibernate.discriminator.ignore_explicit_for_joined";

    /**
     * Each mapped entity class's hierarchy and attributes, read once from the persisters. It holds no reference to
     * the factory, so a renderer cached per factory doesn't keep a closed one reachable.
     */
    private final Map<Class<?>, Mapped> entities;

    /** The line adapter whose probe passed on the factory, or {@code null}, when every preflight is refused. */
    private final PreflightTranslation translation;

    HibernateTreatRenderer(SessionFactoryImplementor factory) {
        this.translation = LINES.stream().filter(line -> line.probes(factory)).findFirst().orElse(null);
        Map<String, Object> properties = factory.getProperties();
        boolean joinedSettings = on(properties.get(IMPLICIT_FOR_JOINED))
                || on(properties.get(IGNORE_EXPLICIT_FOR_JOINED));
        Map<String, List<EntityPersister>> byRoot = new HashMap<>();
        factory.getMappingMetamodel().forEachEntityDescriptor(persister ->
                byRoot.computeIfAbsent(persister.getRootEntityName(), name -> new ArrayList<>()).add(persister));
        Map<Class<?>, Mapped> mapped = new HashMap<>();
        byRoot.forEach((name, members) -> {
            EntityPersister root = factory.getMappingMetamodel().getEntityDescriptor(name);
            Hierarchy hierarchy = hierarchy(root, members, joinedSettings);
            for (EntityPersister member : members) {
                // An entity class mapped under two entity names can't be told apart from a site: refused.
                mapped.merge(member.getMappedClass(), new Mapped(hierarchy, attributes(member)),
                        (first, second) -> new Mapped(Hierarchy.refused(member.getMappedClass().getName()
                                + " is mapped by more than one entity name"), Map.of()));
            }
        });
        this.entities = Map.copyOf(mapped);
    }

    @Override
    @Incubating
    @SuppressWarnings("unchecked")
    public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
        if (from instanceof Root<?>) {
            return cb.treat((Root<T>) from, subtype);
        }
        if (from instanceof ListJoin<?, ?>) {
            return cb.treat((ListJoin<?, T>) from, subtype);
        }
        if (from instanceof SetJoin<?, ?>) {
            return cb.treat((SetJoin<?, T>) from, subtype);
        }
        if (from instanceof CollectionJoin<?, ?>) {
            return cb.treat((CollectionJoin<?, T>) from, subtype);
        }
        if (from instanceof MapJoin<?, ?, ?>) {
            return cb.treat((MapJoin<?, ?, T>) from, subtype);
        }
        return cb.treat((Join<?, T>) from, subtype);
    }

    @Override
    @Incubating
    public Optional<String> refusal(TreatSite site) {
        Set<TreatUse> uses = site.uses();
        if (uses.contains(TreatUse.CORRELATION)) {
            return Optional.of("a sub-select correlated on a treated outer node is not measured");
        }
        if (uses.contains(TreatUse.TYPE)) {
            return typeRefusal(site);
        }
        if (site.shape() != StatementShape.CONTENT && site.shape() != StatementShape.COUNT) {
            return Optional.of("no " + site.shape() + " cell is measured");
        }
        if (!site.anchored()) {
            return Optional.of("a statement that reads the root only through the treat is not measured");
        }
        Mapped entity = entities.get(site.entity());
        if (entity == null) {
            return Optional.of(site.entity().getName() + " is not a mapped entity");
        }
        Hierarchy hierarchy = entity.hierarchy();
        if (hierarchy.refusal().isPresent()) {
            return hierarchy.refusal();
        }
        boolean count = site.shape() == StatementShape.COUNT;
        if (site.attribute().isEmpty()) {
            return count && COUNT_JOINS.contains(uses) ? Optional.empty()
                    : Optional.of("an unmeasured use set for a treated join");
        }
        String attribute = site.attribute().get();
        boolean shared = false;
        if (hierarchy.singleTable()) {
            // A dotted path's columns are its first segment's: the subtype's own attribute, or its foreign key.
            int dot = attribute.indexOf('.');
            String first = dot < 0 ? attribute : attribute.substring(0, dot);
            Mapped subtype = entities.get(site.subtype());
            Property read = subtype == null ? null : subtype.attributes().get(first);
            if (read == null) {
                return Optional.of(site.subtype().getSimpleName() + "." + first + " maps no column the renderer "
                        + "read, so whether another entity of the hierarchy maps it is unknown");
            }
            shared = hierarchy.shared(read.columns());
            if (shared && (dot >= 0 || read.association())) {
                return Optional.of("a join or path through a column another entity of the hierarchy maps is not "
                        + "measured");
            }
        }
        Set<Set<TreatUse>> cells;
        if (shared && hierarchy.variant()) {
            return Optional.of("a shared column in a mapping variant is not measured");
        } else if (shared) {
            cells = count ? SHARED_COUNT : SHARED_CONTENT;
        } else if (hierarchy.variant()) {
            cells = count ? VARIANT_COUNT : CONTENT;
        } else {
            cells = count ? COUNT : CONTENT;
        }
        if (cells.contains(uses)) {
            return Optional.empty();
        }
        return Optional.of(shared ? "an unmeasured use set for a column another entity of the hierarchy maps"
                : hierarchy.variant() ? "an unmeasured use set for this mapping variant" : "an unmeasured use set");
    }

    /**
     * A {@code typeOf} or {@code ofType} site's refusal: its shape's measured {@code TYPE} cells, and an order over a
     * {@code JOINED} root without a discriminator, which renders {@code order by case ... end}, invalid on every
     * vendor and line (R-COL-36); and a count of type groups over such a root, which {@code createCountQuery} counts
     * without the subtype joins, so the engine counts those groups client-side (D-159).
     */
    private Optional<String> typeRefusal(TreatSite site) {
        Set<Set<TreatUse>> cells = switch (site.shape()) {
            case CONTENT -> TYPE_CONTENT;
            case COUNT -> TYPE_COUNT;
            case GROUPED, GROUPED_COUNT -> TYPE_GROUPED;
            default -> null;
        };
        if (cells == null) {
            return Optional.of("no " + site.shape() + " cell is measured for a type read");
        }
        boolean grouped = site.shape() == StatementShape.GROUPED || site.shape() == StatementShape.GROUPED_COUNT;
        if (!grouped && !site.anchored()) {
            return Optional.of("a type read in a statement that doesn't read its root is not measured");
        }
        Mapped entity = entities.get(site.entity());
        if (entity == null) {
            return Optional.of(site.entity().getName() + " is not a mapped entity");
        }
        if (entity.hierarchy().refusal().isPresent()) {
            return entity.hierarchy().refusal();
        }
        if (!cells.contains(site.uses())) {
            return Optional.of("an unmeasured use set for a type read");
        }
        if (site.shape() == StatementShape.GROUPED_COUNT && !entity.hierarchy().typeOrdered()) {
            return Optional.of("the provider's count of type groups on a JOINED root without a discriminator drops "
                    + "the subtype joins (createCountQuery), so the groups are counted client-side");
        }
        if (site.uses().contains(TreatUse.ORDER) && !entity.hierarchy().typeOrdered()) {
            return Optional.of("a typeOf order on a JOINED root without a discriminator renders an invalid "
                    + "order by case ... end; map a discriminator column or order by another column");
        }
        return Optional.empty();
    }

    @Override
    @Incubating
    public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape,
            List<ExpectedNarrowing> expected) {
        Objects.requireNonNull(em, "em");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(expected, "expected");
        if (shape == StatementShape.WRITE || !(statement instanceof CriteriaQuery<?> select)) {
            return Optional.of("only a select statement is preflighted");
        }
        if (translation == null) {
            return Optional.of("no preflight adapter translates statements on Hibernate " + Version.getVersionString());
        }
        try {
            QueryImplementor<?> query = em.unwrap(Session.class).createQuery(select).unwrap(QueryImplementor.class);
            if (!(query.unwrap(SqmQuery.class).getSqmStatement() instanceof SqmSelectStatement<?> sqm)) {
                return Optional.of("the statement is not translated as a select");
            }
            return TreatPreflight.walk(translation.translate(query.getSession().getFactory(), query, sqm),
                    List.copyOf(expected), sqm, query.getSession().getFactory());
        } catch (RuntimeException | LinkageError failed) {
            // Fails closed: a statement the adapter can't translate, or a line whose internals moved, is refused.
            return Optional.of("the statement could not be translated for the preflight: " + failed);
        }
    }

    /** Reads a hierarchy's strategy, gates, variant and column owners from its persisters (AC-VND-18). */
    private static Hierarchy hierarchy(EntityPersister root, List<EntityPersister> members, boolean joinedSettings) {
        Class<?> rootClass = root.getMappedClass();
        if (!root.hasIdentifierProperty() || root.getIdentifierType().isComponentType()) {
            return Hierarchy.refused("an @IdClass or @EmbeddedId hierarchy's composite id is not measured");
        }
        for (EntityPersister member : members) {
            for (Class<?> type = member.getMappedClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                DiscriminatorValue value = type.getDeclaredAnnotation(DiscriminatorValue.class);
                if (value != null && ("null".equalsIgnoreCase(value.value())
                        || "not null".equalsIgnoreCase(value.value()))) {
                    return Hierarchy.refused("a null or not-null discriminator value misclassifies treated rows");
                }
                if (type != rootClass && rootClass.isAssignableFrom(type) && restricted(type)) {
                    return Hierarchy.refused("a subtype @SQLRestriction is ignored by Hibernate 6.6 (7.4 refuses it "
                            + "at bootstrap)");
                }
            }
        }
        boolean secondary = members.stream().anyMatch(member -> secondary(member.getMappedClass())
                || member instanceof SingleTableEntityPersister single && single.getTableSpan() > 1);
        DiscriminatorOptions options = rootClass.getAnnotation(DiscriminatorOptions.class);
        boolean formula = root instanceof AbstractEntityPersister p && p.getDiscriminatorFormulaTemplate() != null;
        if (root instanceof SingleTableEntityPersister) {
            if (options != null && !(options.force() && options.insert())) {
                return Hierarchy.refused("@DiscriminatorOptions other than force = true is not measured");
            }
            return new Hierarchy(Optional.empty(), true, options != null || formula || secondary, true,
                    owners(members));
        }
        if (options != null || formula || secondary) {
            return Hierarchy.refused("a discriminator option, formula or secondary table is measured on "
                    + "SINGLE_TABLE only");
        }
        if (root instanceof JoinedSubclassEntityPersister joined) {
            // Without a physical discriminator Hibernate reads the type from a CASE over the subtype tables.
            boolean discriminator = joined.getDiscriminatorMapping() != null
                    && joined.getDiscriminatorMapping().hasPhysicalColumn();
            return new Hierarchy(Optional.empty(), false, joinedSettings || discriminator, discriminator, Map.of());
        }
        if (root instanceof UnionSubclassEntityPersister) {
            return new Hierarchy(Optional.empty(), false, false, true, Map.of());
        }
        return Hierarchy.refused("an unrecognised persister " + root.getClass().getSimpleName());
    }

    /**
     * Each attribute {@code member} maps, its id included, with its physical columns and whether it is an
     * association. A collection maps none of the hierarchy's columns; an attribute whose columns can't be read is
     * left out, so a {@code SINGLE_TABLE} site over it is refused.
     */
    private static Map<String, Property> attributes(EntityPersister member) {
        if (!(member instanceof AbstractEntityPersister p)) {
            return Map.of();
        }
        Map<String, Property> attributes = new HashMap<>();
        if (p.getIdentifierPropertyName() != null) {
            attributes.put(p.getIdentifierPropertyName(), new Property(columns(p.getIdentifierColumnNames()), false));
        }
        String[] names = p.getPropertyNames();
        Type[] types = p.getPropertyTypes();
        for (int i = 0; i < names.length; i++) {
            try {
                List<String> columns = types[i].isCollectionType() ? List.of()
                        : columns(p.getPropertyColumnNames(names[i]));
                attributes.put(names[i], new Property(columns, types[i].isAssociationType()));
            } catch (RuntimeException unreadable) {
                // Left out: refused on SINGLE_TABLE, where it might share a column.
            }
        }
        return Map.copyOf(attributes);
    }

    private static List<String> columns(String[] names) {
        return Arrays.stream(names).map(HibernateTreatRenderer::normalise).toList();
    }

    /** Each physical column a {@code SINGLE_TABLE} hierarchy maps, and the entities declaring it (AC-COL-51). */
    private static Map<String, Set<String>> owners(List<EntityPersister> members) {
        Map<String, Set<String>> owners = new HashMap<>();
        for (EntityPersister member : members) {
            if (!(member instanceof AbstractEntityPersister p)) {
                continue;
            }
            EntityMappingType parent = p.getSuperMappingType();
            Set<String> inherited = parent == null ? Set.of() : Set.of(parent.getEntityPersister().getPropertyNames());
            Map<String, Property> attributes = attributes(p);
            for (String property : p.getPropertyNames()) {
                if (inherited.contains(property) || !attributes.containsKey(property)) {
                    continue;
                }
                for (String column : attributes.get(property).columns()) {
                    owners.computeIfAbsent(column, name -> new HashSet<>()).add(p.getEntityName());
                }
            }
        }
        owners.replaceAll((column, entities) -> Set.copyOf(entities));
        return Map.copyOf(owners);
    }

    /** Whether {@code type} itself carries {@code @SQLRestriction} or 6.6's deprecated {@code @Where}. */
    private static boolean restricted(Class<?> type) {
        for (Annotation annotation : type.getDeclaredAnnotations()) {
            String name = annotation.annotationType().getName();
            if (annotation instanceof SQLRestriction || "org.hibernate.annotations.Where".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean secondary(Class<?> type) {
        return type.isAnnotationPresent(SecondaryTable.class) || type.isAnnotationPresent(SecondaryTables.class);
    }

    private static boolean on(Object value) {
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String normalise(String column) {
        return column.replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * {@code PREDICATE} alone or with any of {@code NEGATED}, {@code DISJUNCTION} and {@code SUBQUERY},
     * {@code NULL_TEST} alone or with any of {@code NEGATED} and {@code DISJUNCTION}, and the null-accepting
     * {@code ne} shape with and without {@code SUBQUERY}.
     */
    private static List<String> predicates() {
        List<String> sets = new ArrayList<>();
        for (String operand : List.of("PREDICATE", "NULL_TEST")) {
            // A null test under a path exists also reports that exists' PREDICATE, so it never runs as NULL_TEST
            // with SUBQUERY alone; every admitted cell is a TCK case (AC-VND-19), so those sets are refused.
            for (int mask = 0; mask < (operand.equals("PREDICATE") ? 8 : 4); mask++) {
                StringBuilder set = new StringBuilder(operand);
                set.append((mask & 1) != 0 ? "+NEGATED" : "").append((mask & 2) != 0 ? "+DISJUNCTION" : "")
                        .append((mask & 4) != 0 ? "+SUBQUERY" : "");
                sets.add(set.toString());
            }
        }
        sets.add("PREDICATE+NULL_TEST+DISJUNCTION");
        sets.add("PREDICATE+NULL_TEST+DISJUNCTION+SUBQUERY");
        return sets;
    }

    private static Set<Set<TreatUse>> cells(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream())
                .map(cell -> Arrays.stream(cell.split("\\+")).map(TreatUse::valueOf)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(TreatUse.class))))
                .map(Set::copyOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<Set<TreatUse>> minus(Set<Set<TreatUse>> cells, Set<Set<TreatUse>> removed) {
        return cells.stream().filter(cell -> !removed.contains(cell)).collect(Collectors.toUnmodifiableSet());
    }

    /** An entity class's hierarchy and the attributes it maps, by name. */
    private record Mapped(Hierarchy hierarchy, Map<String, Property> attributes) {}

    /** An attribute's physical columns, normalised, and whether it is an association a path joins through. */
    private record Property(List<String> columns, boolean association) {}

    /**
     * A hierarchy's gate refusal, whether it is {@code SINGLE_TABLE}, whether it is a measured mapping variant,
     * whether a {@code typeOf} order over it renders (a discriminator column, a formula or {@code clazz_}), and its
     * shared-column owners.
     */
    private record Hierarchy(Optional<String> refusal, boolean singleTable, boolean variant, boolean typeOrdered,
            Map<String, Set<String>> owners) {

        static Hierarchy refused(String reason) {
            return new Hierarchy(Optional.of(reason), false, false, false, Map.of());
        }

        /** Whether any of {@code columns} is mapped by more than one entity of a {@code SINGLE_TABLE} hierarchy. */
        boolean shared(List<String> columns) {
            return columns.stream().anyMatch(column -> owners.getOrDefault(column, Set.of()).size() > 1);
        }
    }
}
