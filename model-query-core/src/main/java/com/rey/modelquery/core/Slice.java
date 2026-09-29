package com.rey.modelquery.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One page of models plus what is known about the rest: its number and size, whether another page follows, and the
 * total when it was counted. Immutable.
 *
 * @param content the models of the page, in order; an unmodifiable copy
 * @param pageNumber the page number, counted from zero
 * @param pageSize the page size asked for, not the number of rows returned
 * @param hasNext whether rows exist beyond this page
 * @param total the exact number of rows behind the query, empty when not counted
 * @param <M> the model type
 * @implSpec R-EXE-02
 */
@Incubating
public record Slice<M>(List<M> content, int pageNumber, int pageSize, boolean hasNext, OptionalLong total) {

    /** Copies {@code content}, so the slice cannot change after it is built. */
    public Slice {
        content = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(content, "content")));
        Objects.requireNonNull(total, "total");
    }
}
