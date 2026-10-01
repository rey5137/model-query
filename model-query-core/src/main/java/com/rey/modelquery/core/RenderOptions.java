package com.rey.modelquery.core;

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
public final class RenderOptions {

    /** The limits of the {@code OTHER} profile, which are safe on any database (R-VND-06). */
    private static final RenderOptions PORTABLE = new RenderOptions(1_000, 2_000, null);

    private final int maxInListSize;
    private final int maxBindParameters;
    private final NullPrecedenceRenderer nullPrecedenceRenderer;

    private RenderOptions(int maxInListSize, int maxBindParameters,
            NullPrecedenceRenderer nullPrecedenceRenderer) {
        this.maxInListSize = maxInListSize;
        this.maxBindParameters = maxBindParameters;
        this.nullPrecedenceRenderer = nullPrecedenceRenderer;
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
        return new RenderOptions(maxInListSize, maxBindParameters, null);
    }

    /** These options with {@code renderer} rendering explicit null precedence. */
    public RenderOptions withNullPrecedenceRenderer(NullPrecedenceRenderer renderer) {
        return new RenderOptions(maxInListSize, maxBindParameters, Objects.requireNonNull(renderer, "renderer"));
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
}
