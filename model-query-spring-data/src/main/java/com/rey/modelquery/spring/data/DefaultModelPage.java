package com.rey.modelquery.spring.data;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;

/**
 * A {@link ModelPage} over Spring Data's {@link SliceImpl}, with a total that is {@code null} when not counted. It
 * wraps the slice rather than extending it, so {@code equals} stays symmetric: a {@code SliceImpl} accepts any
 * subclass as equal and would ignore the total.
 *
 * @implSpec R-SPR-07
 */
final class DefaultModelPage<M> implements ModelPage<M>, Serializable {

    private static final long serialVersionUID = 1L;

    private final SliceImpl<M> slice;
    private final Long total;

    DefaultModelPage(List<M> content, Pageable pageable, boolean hasNext, Long total) {
        this.slice = new SliceImpl<>(content, pageable, hasNext);
        this.total = total;
    }

    @Override
    public Long getTotalElements() {
        return total;
    }

    @Override
    public Integer getTotalPages() {
        if (total == null) {
            return null;
        }
        long size = getSize();
        return Math.toIntExact(total / size + (total % size == 0 ? 0 : 1));
    }

    @Override
    public <U> ModelPage<U> map(Function<? super M, ? extends U> converter) {
        Slice<U> converted = slice.map(converter);
        return new DefaultModelPage<>(converted.getContent(), getPageable(), hasNext(), total);
    }

    @Override
    public int getNumber() {
        return slice.getNumber();
    }

    @Override
    public int getSize() {
        return slice.getSize();
    }

    @Override
    public int getNumberOfElements() {
        return slice.getNumberOfElements();
    }

    @Override
    public List<M> getContent() {
        return slice.getContent();
    }

    @Override
    public boolean hasContent() {
        return slice.hasContent();
    }

    @Override
    public Sort getSort() {
        return slice.getSort();
    }

    @Override
    public boolean isFirst() {
        return slice.isFirst();
    }

    @Override
    public boolean isLast() {
        return slice.isLast();
    }

    @Override
    public boolean hasNext() {
        return slice.hasNext();
    }

    @Override
    public boolean hasPrevious() {
        return slice.hasPrevious();
    }

    @Override
    public Pageable getPageable() {
        return slice.getPageable();
    }

    @Override
    public Pageable nextPageable() {
        return slice.nextPageable();
    }

    @Override
    public Pageable previousPageable() {
        return slice.previousPageable();
    }

    @Override
    public Iterator<M> iterator() {
        return slice.iterator();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DefaultModelPage<?> other && slice.equals(other.slice)
                && Objects.equals(total, other.total);
    }

    @Override
    public int hashCode() {
        return 31 * slice.hashCode() + Objects.hashCode(total);
    }

    @Override
    public String toString() {
        return slice + (total == null ? " of an uncounted total" : " of " + total + " rows");
    }
}
