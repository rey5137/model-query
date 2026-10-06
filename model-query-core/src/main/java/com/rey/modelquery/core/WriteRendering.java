package com.rey.modelquery.core;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What {@link ModelUpdate} and {@link ModelDelete} share: the predicate choosing a write's rows, and the checks of a
 * write definition against the JPA metamodel that {@code build()} cannot run (INV-7, D-61). Stateless.
 *
 * @implSpec R-WRT-08, R-WRT-10, R-WRT-11, R-WRT-13, D-61, D-63
 */
final class WriteRendering {

    private WriteRendering() {}

    /**
     * The distinct keys of {@code modelKeys}, each converted to its attribute value through its column's converter (a
     * list of component values for a composite key), in first-seen order: two keys that convert to one attribute
     * value write one row, so they are one key (R-WRT-08, D-63). The result and each composite key are copies, so a
     * caller's list changed later leaves them alone (INV-9).
     *
     * @throws IllegalArgumentException for a composite key with the wrong number of components
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <M> List<Object> distinctKeys(PrimaryKey<M, ?> key, List<Object> modelKeys) {
        List<ColumnField<M, ?, ?>> columns = key.columns();
        var distinct = new LinkedHashSet<Object>();
        for (Object modelKey : modelKeys) {
            if (columns.size() == 1) {
                distinct.add(((ColumnField) columns.get(0)).toAttribute(modelKey));
                continue;
            }
            List<?> values = (List<?>) modelKey;
            if (values.size() != columns.size()) {
                throw new IllegalArgumentException("a key of " + columns.size() + " components has "
                        + values.size() + ": " + values);
            }
            Object[] attributes = new Object[columns.size()];
            for (int c = 0; c < attributes.length; c++) {
                attributes[c] = ((ColumnField) columns.get(c)).toAttribute(values.get(c));
            }
            distinct.add(Collections.unmodifiableList(Arrays.asList(attributes)));
        }
        return Collections.unmodifiableList(new ArrayList<>(distinct));
    }

    /**
     * The model key of {@code attributeKey}, a key as {@link #distinctKeys} or a key select returns it, converted back
     * through each column's converter; a composite key's is the list of its components' model values (R-WRT-20,
     * D-63).
     */
    static <M> Object modelKey(PrimaryKey<M, ?> key, Object attributeKey) {
        List<ColumnField<M, ?, ?>> columns = key.columns();
        if (columns.size() == 1) {
            return columns.get(0).toModel(attributeKey);
        }
        List<?> values = (List<?>) attributeKey;
        Object[] model = new Object[columns.size()];
        for (int c = 0; c < model.length; c++) {
            model[c] = columns.get(c).toModel(values.get(c));
        }
        return List.of(model);
    }

    /**
     * The predicates choosing the rows of {@code root}, the statement's root: {@code key IN (keys)} unless
     * {@code keys} is {@code null}, then the {@code where} tree. A tree that needs no join renders on the root
     * itself; one that needs any join renders whole inside one {@code EXISTS} over a second root of the entity,
     * correlated by the key columns, since a bulk statement has no joins and splitting the tree per predicate would
     * change what {@code not} and {@code or} mean (R-WRT-10). With {@code rootTermsOnly}, only the tree's root terms
     * render, as a key-first write re-applies them (R-WRT-11).
     *
     * @param keys attribute-value keys, as {@link #distinctKeys} returns them, or {@code null} for no key predicate
     */
    static <E, M> List<Predicate> rows(List<Object> keys, List<Filter> where, PrimaryKey<M, ?> key,
            CommonAbstractCriteria statement, Root<E> root, JoinContext ctx, CriteriaBuilder cb, RenderOptions options,
            boolean rootTermsOnly) {
        var predicates = new ArrayList<Predicate>();
        if (keys != null) {
            predicates.add(key.in(keys, ctx, cb, true));
        }
        if (where.isEmpty()) {
            return predicates;
        }
        if (rootTermsOnly) {
            for (Filter term : where) {
                if (!probe(List.of(term), statement, root.getModel().getJavaType(), cb, options).joinsOrNests()) {
                    predicates.addAll(ConditionGroup.toPredicates(List.of(term), ctx));
                }
            }
            return predicates;
        }
        Subquery<Integer> sub = statement.subquery(Integer.class);
        Root<E> inner = sub.from(root.getModel().getJavaType());
        JoinContext innerCtx = JoinContext.of(inner, cb, sub, options);
        List<Predicate> tree = ConditionGroup.toPredicates(where, innerCtx);
        if (inner.getJoins().isEmpty()) {
            // Rendered again on the statement's root; the sub-query is dropped unused.
            predicates.addAll(ConditionGroup.toPredicates(where, ctx));
        } else {
            // Even with no predicate left: an INNER join the tree made narrows the rows, as it does on a read.
            var correlated = new ArrayList<Predicate>();
            for (ColumnField<M, ?, ?> column : key.columns()) {
                correlated.add(cb.equal(column.path(innerCtx), column.path(ctx)));
            }
            correlated.addAll(tree);
            sub.select(cb.literal(1)).where(correlated.toArray(Predicate[]::new));
            predicates.add(cb.exists(sub));
            // The sub-query is part of the statement, so the values its memoised expressions repeat count too.
            ctx.addRepeatedExpressionBinds(innerCtx);
        }
        return predicates;
    }

    /**
     * The entity types the sub-queries of the statement {@link #rows} renders for {@code where} read: {@code entity}
     * when the tree needs a join, so renders in an {@code EXISTS} over it (R-WRT-10), and each type an
     * {@code exists(...)} path joins (R-WRT-11). Empty when the statement has no sub-query. An executor tells from them
     * whether the statement reads its own table; never a table name here (INV-7, D-109). Rendered into a scratch query,
     * since a filter is a lambda with no structure to walk (D-65).
     */
    static Set<Class<?>> entitiesReadInSubquery(List<Filter> where, Class<?> entity, CriteriaBuilder cb,
            RenderOptions options) {
        if (where.isEmpty()) {
            return Set.of();
        }
        Probe probe = probe(where, cb.createQuery(), entity, cb, options);
        Set<Class<?>> read = new LinkedHashSet<>(probe.ctx().existsJoined());
        if (probe.joined()) {
            read.add(entity);
        }
        return Collections.unmodifiableSet(read);
    }

    /**
     * The key select of a key-first or chunked write: the primary-key columns of {@code entity}'s rows that
     * {@code key IN (keys)}, unless {@code keys} is {@code null}, and the {@code where} tree choose, the tree rendered
     * as a read renders it, joins included, with no order (R-WRT-11, R-WRT-17, D-63).
     */
    static <E, M> BuiltQuery<M> keySelect(List<Object> keys, List<Filter> where, PrimaryKey<M, ?> key,
            Class<E> entity, CriteriaBuilder cb, RenderOptions options, String model) {
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> from = query.from(entity);
        JoinContext joins = JoinContext.of(from, cb, query, options);
        RowSelection selection = RowSelection.of(key.columns());
        query.multiselect(selection.selections(joins));
        var predicates = new ArrayList<Predicate>();
        if (keys != null) {
            predicates.add(key.in(keys, joins, cb, true));
        }
        predicates.addAll(ConditionGroup.toPredicates(where, joins));
        if (!predicates.isEmpty()) {
            query.where(predicates.toArray(Predicate[]::new));
        }
        return new BuiltQuery<>(query, joins, selection, row -> {
            throw new UnsupportedOperationException(model + ": a key select maps no model; read its keys");
        });
    }

    /** {@code where} rendered over a second root of {@code entity} in a sub-query of {@code statement}. */
    private static Probe probe(List<Filter> where, CommonAbstractCriteria statement, Class<?> entity,
            CriteriaBuilder cb, RenderOptions options) {
        Subquery<Integer> sub = statement.subquery(Integer.class);
        Root<?> inner = sub.from(entity);
        JoinContext ctx = JoinContext.of(inner, cb, sub, options);
        ConditionGroup.toPredicates(where, ctx);
        return new Probe(!inner.getJoins().isEmpty(), ctx);
    }

    /** What rendering a tree into a scratch sub-query showed: whether it joined, and its join context. */
    private record Probe(boolean joined, JoinContext ctx) {

        /** Whether the tree needs a join or a sub-query, so is not a root term (R-WRT-11). */
        boolean joinsOrNests() {
            return joined || ctx.renderedExists();
        }
    }

    /**
     * The keys to render: all of the definition's distinct keys when {@code chunk} is {@code null}, else
     * {@code chunk}, which must be a non-empty run of them; {@code null} when the definition chose its rows without
     * keys.
     *
     * @throws IllegalArgumentException for a {@code chunk} that is empty or given to a definition without keys
     */
    static List<Object> keysToRender(List<Object> distinct, List<?> chunk) {
        if (chunk == null) {
            return distinct;
        }
        if (distinct == null || chunk.isEmpty()) {
            throw new IllegalArgumentException(distinct == null
                    ? "a write without whereKey or whereKeys takes no keys"
                    : "a chunk of keys is never empty, since an empty one would leave the rows unchosen");
        }
        return List.copyOf(chunk);
    }

    /**
     * {@code keys}, the keys a key select chose, to render in place of the definition's own (R-WRT-11, D-63).
     *
     * @throws IllegalArgumentException for empty {@code keys}, which would leave the rows unchosen
     */
    static List<Object> selectedKeys(List<?> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("a run of selected keys is never empty, since an empty one would leave "
                    + "the rows unchosen");
        }
        return List.copyOf(keys);
    }

    /**
     * Checks that {@code key} names exactly the id attributes of {@code entity}: the {@code @Id} attribute, the
     * {@code @EmbeddedId} or its components, or the {@code @IdClass} attributes, all on {@code root}. A key that is
     * not the id could match several rows (R-WRT-08).
     *
     * @throws ModelQueryDefinitionException {@code MQ1608} otherwise
     */
    static void checkKey(EntityType<?> entity, TableField<?, ?> root, PrimaryKey<?, ?> key, String model) {
        var names = new HashSet<String>();
        boolean onRoot = true;
        for (ColumnField<?, ?, ?> column : key.columns()) {
            names.add(column.name());
            onRoot &= column.table().key().equals(root.key());
        }
        Set<String> ids = idNames(entity);
        boolean matches = onRoot && names.size() == key.columns().size()
                && (names.equals(ids) || names.equals(embeddedIdComponents(entity, ids)));
        if (!matches) {
            throw new ModelQueryDefinitionException(MqCode.MQ1608, model + ": the primary key " + key.columns()
                    + " is not the id " + ids + " of " + entity.getJavaType().getSimpleName()
                    + "; a key that is not the id could write several rows per key");
        }
    }

    /**
     * Checks that no assignment writes an id attribute or the {@code @Version} attribute, either whole or through an
     * embedded path (R-WRT-13).
     *
     * @throws ModelQueryDefinitionException {@code MQ1605} otherwise
     */
    static void checkAssignable(EntityType<?> entity, List<? extends Assignment<?, ?>> assignments) {
        Set<String> ids = idNames(entity);
        Optional<String> version = version(entity).map(Attribute::getName);
        for (Assignment<?, ?> assignment : assignments) {
            String name = assignment.column().name();
            int dot = name.indexOf('.');
            String head = dot < 0 ? name : name.substring(0, dot);
            if (ids.contains(head) || version.filter(head::equals).isPresent()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1605, assignment.column() + ": "
                        + entity.getJavaType().getSimpleName() + "." + head + " is "
                        + (ids.contains(head) ? "an id attribute" : "the @Version attribute")
                        + ", which a bulk update never assigns");
            }
        }
    }

    /** The {@code @Version} attribute of {@code entity}, if it has one. */
    static Optional<SingularAttribute<?, ?>> version(EntityType<?> entity) {
        if (!entity.hasVersionAttribute()) {
            return Optional.empty();
        }
        for (SingularAttribute<?, ?> attribute : entity.getSingularAttributes()) {
            if (attribute.isVersion()) {
                return Optional.of(attribute);
            }
        }
        return Optional.empty();
    }

    /** The names of the id attributes: the one {@code @Id} or {@code @EmbeddedId}, or the {@code @IdClass} ones. */
    static Set<String> idNames(EntityType<?> entity) {
        var names = new HashSet<String>();
        if (entity.hasSingleIdAttribute()) {
            for (SingularAttribute<?, ?> attribute : entity.getSingularAttributes()) {
                if (attribute.isId()) {
                    names.add(attribute.getName());
                }
            }
        } else {
            entity.getIdClassAttributes().forEach(attribute -> names.add(attribute.getName()));
        }
        return names;
    }

    /** {@code id.a}, {@code id.b} for an {@code @EmbeddedId id} of attributes {@code a} and {@code b}; else empty. */
    private static Set<String> embeddedIdComponents(EntityType<?> entity, Set<String> ids) {
        var names = new HashSet<String>();
        for (SingularAttribute<?, ?> attribute : entity.getSingularAttributes()) {
            if (attribute.isId() && attribute.getType() instanceof EmbeddableType<?> embeddable) {
                embeddable.getAttributes().forEach(part -> names.add(attribute.getName() + "." + part.getName()));
            }
        }
        return ids.size() == 1 ? names : Set.of();
    }
}
