package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A named filter operator applied with {@link Filters#op} and {@link Having#op}: its {@link OperatorRenderer} builds
 * the predicate over the column and an operand the engine binds itself, so the operand never reaches SQL as a literal.
 *
 * <p>Immutable and shareable (INV-9): it holds a name, its value type, an optional attribute check, an optional
 * feature, an optional {@code prepare} and a renderer, and no {@code CriteriaBuilder}. {@link #requiringAttribute},
 * {@link #requires} and {@link #preparing} each return a new instance that replaces that setting (D-168), so an
 * operator belongs in a {@code static final} constant, never built at a call site. Equality is identity: two
 * definitions with one name are two operators, and a recorded condition compares its operator the same way
 * (R-INS-13). The name is for logs, inspection and messages only.
 *
 * @param <C> the type of the column it applies to
 * @param <V> the type of its operand: {@code C} for {@link #of(String, OperatorRenderer)}
 * @implSpec api/12 R-FLT-20, R-FLT-21, api/16 R-INS-13, D-167, D-168
 */
@Incubating
public final class FilterOperator<C, V> {

    private final String name;
    /** The declared operand type of the {@code <C, V>} form, or {@code null} when the value is of the column's type. */
    private final Class<?> valueType;
    private final Class<?> attributeType;
    private final OperatorFeature feature;
    private final UnaryOperator<V> prepare;
    private final OperatorRenderer<C, V> renderer;

    private FilterOperator(String name, Class<?> valueType, Class<?> attributeType, OperatorFeature feature,
            UnaryOperator<V> prepare, OperatorRenderer<C, V> renderer) {
        this.name = name;
        this.valueType = valueType;
        this.attributeType = attributeType;
        this.feature = feature;
        this.prepare = prepare;
        this.renderer = renderer;
    }

    /**
     * An operator whose operand is of the column's own type: a value is converted as {@code eq}'s is and bound typed
     * by the column's attribute (R-FLT-21).
     *
     * @throws ModelQueryDefinitionException {@code MQ1301} for a null or blank name
     */
    public static <C> FilterOperator<C, C> of(String name, OperatorRenderer<C, C> renderer) {
        return new FilterOperator<>(named(name), null, null, null, null, Objects.requireNonNull(renderer, "renderer"));
    }

    /**
     * An operator whose operand is a {@code V}, bound typed by {@code valueType} and never converted (R-FLT-21), unlike
     * {@link #of(String, OperatorRenderer)}'s: on a {@code String}-to-{@code String} {@code ColumnConverter} column the
     * two bind differently, so use that overload when the value is a column value.
     *
     * @throws ModelQueryDefinitionException {@code MQ1301} for a null or blank name
     */
    public static <C, V> FilterOperator<C, V> of(String name, Class<V> valueType, OperatorRenderer<C, V> renderer) {
        return new FilterOperator<>(named(name), ColumnField.boxed(Objects.requireNonNull(valueType, "valueType")),
                null, null, null, Objects.requireNonNull(renderer, "renderer"));
    }

    /**
     * The same operator, refused with {@code MQ1001} when the filter is added on a column whose attribute is not an
     * {@code attributeType}: a converted column's attribute type, an aggregate's type in {@code having} (R-COL-14).
     */
    public FilterOperator<C, V> requiringAttribute(Class<?> attributeType) {
        return new FilterOperator<>(name, valueType,
                ColumnField.boxed(Objects.requireNonNull(attributeType, "attributeType")), feature, prepare, renderer);
    }

    /**
     * The same operator, refused before any statement where the vendor or the provider lacks {@code feature}; with no
     * operator gate it is {@code MQ4014} (R-FLT-20).
     */
    public FilterOperator<C, V> requires(OperatorFeature feature) {
        return new FilterOperator<>(name, valueType, attributeType, Objects.requireNonNull(feature, "feature"),
                prepare, renderer);
    }

    /**
     * The same operator with its value passed through {@code prepare} (escaping, wildcards) when the filter is added,
     * after a converted column's converter for a {@code FilterOperator<C, C>}, so it builds on the attribute value as
     * {@code like} does; the condition records the value unprepared (R-INS-13). A {@code null} from it or a thrown
     * {@code RuntimeException} is {@code MQ1313}; on a converted column whose attribute is not a {@code C} it is
     * {@code MQ1001} (R-FLT-21).
     */
    public FilterOperator<C, V> preparing(UnaryOperator<V> prepare) {
        return new FilterOperator<>(name, valueType, attributeType, feature,
                Objects.requireNonNull(prepare, "prepare"), renderer);
    }

    /** The name it was defined with, for logs, inspection and messages. */
    public String name() {
        return name;
    }

    /** The declared operand type, or {@code null} when the operand is of the column's type. */
    Class<?> valueType() {
        return valueType;
    }

    /** The attribute type {@link #requiringAttribute} requires, or {@code null}. */
    Class<?> attributeType() {
        return attributeType;
    }

    /** The feature {@link #requires} names, or {@code null}. */
    OperatorFeature feature() {
        return feature;
    }

    /** The {@link #preparing} function, or {@code null}. */
    UnaryOperator<V> prepare() {
        return prepare;
    }

    OperatorRenderer<C, V> renderer() {
        return renderer;
    }

    /** The name; the format is not API. */
    @Override
    public String toString() {
        return name;
    }

    private static String named(String name) {
        if (name == null || name.isBlank()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1301,
                    "FilterOperator.of(...) received a null or blank name; name the operator for logs and inspection");
        }
        return name;
    }
}
