package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One keyset page: its models and the cursors that reach the neighbouring pages. A cursor is present exactly when the
 * matching flag is true, so {@code hasNext() == nextCursor().isPresent()}: a last page carries no next cursor and
 * tail-polling is not supported (D-110). A final class, not a record, so accessors can be added later (R-PAG-16).
 *
 * @param <M> the model type
 * @implSpec R-PAG-16, R-PAG-21
 */
@Incubating
public final class KeysetSlice<M> {

    private final List<M> content;
    private final int size;
    private final Optional<String> previousCursor;
    private final Optional<String> nextCursor;

    private KeysetSlice(List<M> content, int size, Optional<String> previousCursor, Optional<String> nextCursor) {
        this.content = content;
        this.size = size;
        this.previousCursor = previousCursor;
        this.nextCursor = nextCursor;
    }

    /**
     * {@code content} as an unmodifiable copy, the page size asked for and the cursors of the neighbouring pages.
     * Public for tests (R-PAG-16).
     */
    public static <M> KeysetSlice<M> of(List<M> content, int size, Optional<String> previous, Optional<String> next) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        return new KeysetSlice<>(Collections.unmodifiableList(new ArrayList<>(content)), size, previous, next);
    }

    /** The models of the page, in the query's order; an unmodifiable copy. */
    public List<M> content() {
        return content;
    }

    /** The page size asked for, not the number of rows returned. */
    public int size() {
        return size;
    }

    /** Whether a page follows: present exactly when {@link #nextCursor()} is. */
    public boolean hasNext() {
        return nextCursor.isPresent();
    }

    /** Whether a page precedes this one: present exactly when {@link #previousCursor()} is. */
    public boolean hasPrevious() {
        return previousCursor.isPresent();
    }

    /** The cursor of the page after this one, or empty on the last page (R-PAG-21). */
    public Optional<String> nextCursor() {
        return nextCursor;
    }

    /** The cursor of the page before this one, or empty on the first page (R-PAG-21). */
    public Optional<String> previousCursor() {
        return previousCursor;
    }

    @Override
    public String toString() {
        return "KeysetSlice[size=" + size + ", content=" + content.size() + ", hasPrevious=" + hasPrevious()
                + ", hasNext=" + hasNext() + "]";
    }
}
