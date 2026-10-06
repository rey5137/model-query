package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.List;
import java.util.Objects;

/**
 * An immutable definition of one insert-model row, which the executor's {@code persist} writes through JPA: an entity
 * write, so the entity's callbacks, Bean Validation and Envers run, and the created row's key comes back for every
 * generator. The row is read once, by {@link #of}, and never again (INV-9). It takes no {@code set}, conflict clause
 * or chunking (R-WRT-40).
 *
 * @param <E> the root entity
 * @param <K> the root's id type, fixed by the generated {@code persist(row)} (D-117)
 * @param <M> the insert model
 * @implSpec R-WRT-39, R-WRT-40, D-61, D-116, D-117
 */
@Incubating
public final class ModelPersist<E, K, M> {

    private final InsertColumns<M, E> columns;
    private final Class<K> keyType;
    private final List<Object> values;

    private ModelPersist(InsertColumns<M, E> columns, Class<K> keyType, List<Object> values) {
        this.columns = columns;
        this.keyType = keyType;
        this.values = values;
    }

    /**
     * A persist of {@code row}, read once now into the definition; the copy is shallow, so a mutable value inside
     * the row is still the caller's.
     *
     * @param keyType the root's id type, which the definition's first execution checks against the provider's
     *     ({@code MQ1807})
     * @throws ModelQueryDefinitionException {@code MQ1803} for a {@code null} row, {@code MQ1802} for a {@code null}
     *     assigned id
     */
    public static <M, E, K> ModelPersist<E, K, M> of(InsertColumns<M, E> columns, Class<K> keyType, M row) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(keyType, "keyType");
        return new ModelPersist<>(columns, keyType, columns.read(row, 0));
    }

    /** The entity the row is written to. */
    public Class<E> rootEntity() {
        return columns.rootEntity();
    }

    /** The root's id type, which the key is returned as. */
    public Class<K> keyType() {
        return keyType;
    }

    /**
     * Checks the definition against {@code metamodel}, which {@code ModelPersist.of} cannot see (INV-7). An executor
     * calls it on the definition's first execution per {@code EntityManagerFactory}, before any statement (D-61).
     *
     * @throws ModelQueryDefinitionException {@code MQ1807} when the key type is not the root's boxed id type, as an
     *     {@code orm.xml} mapping can make it (R-WRT-39, D-117); {@code MQ1805} when a column is set on a record or
     *     an embeddable with no no-arg constructor (R-WRT-39)
     */
    @EngineFacing
    public void checkMetamodel(Metamodel metamodel) {
        EntityType<E> entity = metamodel.entity(rootEntity());
        InsertMetamodel.checkKeyType(entity, keyType, toString());
        for (String attribute : attributes()) {
            InsertMetamodel.checkInstantiable(entity, attribute, toString());
        }
    }

    /**
     * The root attributes the row sets, in column order, dotted through an embeddable; a to-one is named alone, and
     * {@link #attributeValues} holds its target's id (R-WRT-39).
     */
    @EngineFacing
    public List<String> attributes() {
        return columns.columns().stream().map(ColumnField::name).toList();
    }

    /**
     * The row's values in the order of {@link #attributes}: each passed through its column's converter, a
     * {@code null} kept, a to-one's the target's id (R-WRT-39).
     *
     * @throws ModelQueryDefinitionException {@code MQ1308} when a converter cannot convert a value
     */
    @EngineFacing
    public List<Object> attributeValues() {
        return columns.toAttributes(values);
    }

    /** The entity, for the executor's log: never a value (D-95). The format is not API. */
    @Override
    public String toString() {
        return rootEntity().getSimpleName() + " (persist)";
    }

    InsertColumns<M, E> columns() {
        return columns;
    }

    /** The row's column values, in column order. */
    List<Object> values() {
        return values;
    }
}
