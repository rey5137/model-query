package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.Optional;

/**
 * The facts about the target database a query build may render by: IN-list and bind-parameter limits and an
 * optional native null-precedence renderer. Vendor-neutral, so {@code core} names no vendor
 * (INV-6); the executor makes one from its resolved {@code VendorProfile} (D-34). Immutable.
 *
 * @implSpec R-VND-01, R-VND-06
 */
@EngineFacing
@Incubating
public final class RenderOptions {

    /** The limits of the {@code OTHER} profile, which are safe on any database (R-VND-06). */
    private static final RenderOptions PORTABLE = new RenderOptions(1_000, 2_000, null, null, null, null, null, null);

    private final int maxInListSize;
    private final int maxBindParameters;
    private final NullPrecedenceRenderer nullPrecedenceRenderer;
    private final ConstantRenderer constantRenderer;
    private final SequenceRenderer sequenceRenderer;
    private final SourceRenderer sourceRenderer;
    private final SubqueryRenderer subqueryRenderer;
    private final TreatRenderer treatRenderer;

    private RenderOptions(int maxInListSize, int maxBindParameters, NullPrecedenceRenderer nullPrecedenceRenderer,
            ConstantRenderer constantRenderer, SequenceRenderer sequenceRenderer, SourceRenderer sourceRenderer,
            SubqueryRenderer subqueryRenderer, TreatRenderer treatRenderer) {
        this.maxInListSize = maxInListSize;
        this.maxBindParameters = maxBindParameters;
        this.nullPrecedenceRenderer = nullPrecedenceRenderer;
        this.constantRenderer = constantRenderer;
        this.sequenceRenderer = sequenceRenderer;
        this.sourceRenderer = sourceRenderer;
        this.subqueryRenderer = subqueryRenderer;
        this.treatRenderer = treatRenderer;
    }

    /** The options of an unknown database: the {@code OTHER} limits and no renderer. */
    public static RenderOptions portable() {
        return PORTABLE;
    }

    /**
     * Options with the given limits and no native null-precedence renderer.
     *
     * @throws IllegalArgumentException when a limit is not positive, since chunking by it could never advance
     */
    public static RenderOptions of(int maxInListSize, int maxBindParameters) {
        if (maxInListSize < 1 || maxBindParameters < 1) {
            throw new IllegalArgumentException("maxInListSize and maxBindParameters must be positive, got "
                    + maxInListSize + " and " + maxBindParameters);
        }
        return new RenderOptions(maxInListSize, maxBindParameters, null, null, null, null, null, null);
    }

    /** These options with {@code renderer} rendering explicit null precedence. */
    public RenderOptions withNullPrecedenceRenderer(NullPrecedenceRenderer renderer) {
        return new RenderOptions(maxInListSize, maxBindParameters, Objects.requireNonNull(renderer, "renderer"),
                constantRenderer, sequenceRenderer, sourceRenderer, subqueryRenderer, treatRenderer);
    }

    /** These options with {@code renderer} rendering a column-typed constant (R-COL-27). */
    public RenderOptions withConstantRenderer(ConstantRenderer renderer) {
        return new RenderOptions(maxInListSize, maxBindParameters, nullPrecedenceRenderer,
                Objects.requireNonNull(renderer, "renderer"), sequenceRenderer, sourceRenderer, subqueryRenderer,
                treatRenderer);
    }

    /** These options with {@code renderer} rendering a sequence's next value (R-COL-28). */
    public RenderOptions withSequenceRenderer(SequenceRenderer renderer) {
        return new RenderOptions(maxInListSize, maxBindParameters, nullPrecedenceRenderer, constantRenderer,
                Objects.requireNonNull(renderer, "renderer"), sourceRenderer, subqueryRenderer, treatRenderer);
    }

    /** These options with {@code renderer} registering a source's CTE on every statement that reads it (R-SRC-12). */
    public RenderOptions withSourceRenderer(SourceRenderer renderer) {
        return new RenderOptions(maxInListSize, maxBindParameters, nullPrecedenceRenderer, constantRenderer,
                sequenceRenderer, Objects.requireNonNull(renderer, "renderer"), subqueryRenderer, treatRenderer);
    }

    /** These options with {@code renderer} limiting an ordered scalar sub-query to one row (R-VND-25). */
    @Incubating
    public RenderOptions withSubqueryRenderer(SubqueryRenderer renderer) {
        return new RenderOptions(maxInListSize, maxBindParameters, nullPrecedenceRenderer, constantRenderer,
                sequenceRenderer, sourceRenderer, Objects.requireNonNull(renderer, "renderer"), treatRenderer);
    }

    /** These options with the provider's treated-path renderer (R-VND-26). */
    @Incubating
    public RenderOptions withTreatRenderer(TreatRenderer renderer) {
        return new RenderOptions(maxInListSize, maxBindParameters, nullPrecedenceRenderer, constantRenderer,
                sequenceRenderer, sourceRenderer, subqueryRenderer, Objects.requireNonNull(renderer, "renderer"));
    }

    /** The most values one {@code IN} list takes before it is split (R-FLT-09). */
    public int maxInListSize() {
        return maxInListSize;
    }

    /** The most bind parameters one statement takes. */
    public int maxBindParameters() {
        return maxBindParameters;
    }

    /** The provider's native null-precedence renderer, or empty to render the portable form (R-COL-12). */
    public Optional<NullPrecedenceRenderer> nullPrecedenceRenderer() {
        return Optional.ofNullable(nullPrecedenceRenderer);
    }

    /** The provider's column-typed constant renderer, or empty: a typed constant is then {@code MQ1508} (R-COL-27). */
    public Optional<ConstantRenderer> constantRenderer() {
        return Optional.ofNullable(constantRenderer);
    }

    /** The renderer of a sequence's next value, or empty: {@code Expr.nextval} is then {@code MQ1509} (R-COL-28). */
    public Optional<SequenceRenderer> sequenceRenderer() {
        return Optional.ofNullable(sequenceRenderer);
    }

    /**
     * The provider's CTE support for a query rooted at a source, or empty: such a query is then {@code MQ4014},
     * raised at build time, before any statement (R-SRC-11, R-SRC-12).
     */
    public Optional<SourceRenderer> sourceRenderer() {
        return Optional.ofNullable(sourceRenderer);
    }

    /** The ordered scalar sub-query renderer, or empty: {@code Correlated.first} is then {@code MQ4014} (R-VND-25). */
    @Incubating
    public Optional<SubqueryRenderer> subqueryRenderer() {
        return Optional.ofNullable(subqueryRenderer);
    }
    /** The treated-path renderer, or empty: every treated node is then {@code MQ4014} (R-VND-26). */
    @Incubating
    public Optional<TreatRenderer> treatRenderer() {
        return Optional.ofNullable(treatRenderer);
    }
}
