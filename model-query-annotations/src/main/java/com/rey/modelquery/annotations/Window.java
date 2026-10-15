package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a window-function selection over a {@link SourceModel}, mapped into the annotated field (R-PROC-36). It is
 * only allowed on a source model ({@code MQ3033}), because SQL forbids a window function in {@code WHERE} and a keyset
 * predicate is a {@code WHERE}. An outer model reads the value as an ordinary column.
 *
 * <p>{@code argument}, {@code partitionBy} and {@code orderBy} are attribute paths of the source's root entity,
 * checked like {@link Column}'s. In a grouped source each is either a {@code @GroupBy} column's path or the field name
 * of an {@link Aggregate} field of the model.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Window {

    /**
     * The window function.
     *
     * @return the function
     */
    WindowFunction function();

    /**
     * The attribute path the function reads, or the field name of an {@link Aggregate} of the same model. Omitted for
     * the ranking functions {@code ROW_NUMBER}, {@code RANK} and {@code DENSE_RANK}, which read no argument.
     *
     * @return the argument, or {@code ""} for none
     */
    String argument() default "";

    /**
     * The attribute paths that split the rows into partitions; each entry is {@code "path"}. No entries means one
     * partition over every row.
     *
     * @return the partition paths
     */
    String[] partitionBy() default {};

    /**
     * The attribute paths the window is ordered by, each {@code "path"}, {@code "path asc"} or {@code "path desc"},
     * ascending by default. An order-sensitive function must declare one ({@code MQ3032}).
     *
     * @return the order paths
     */
    String[] orderBy() default {};

    /**
     * The frame the function reads. {@link WindowFrame#NONE} declares no clause, which is the database's default.
     *
     * @return the frame
     */
    WindowFrame frame() default WindowFrame.NONE;
}
