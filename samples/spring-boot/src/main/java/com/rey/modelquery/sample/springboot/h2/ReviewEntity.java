package com.rey.modelquery.sample.springboot.h2;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A review of a book. It has no association to {@link BookEntity}; the two roots are related by {@code bookId} alone,
 * which is what recipe 2's sub-selects and correlated {@code exists} need (D-112).
 */
@Entity
@Table(name = "reviews")
public class ReviewEntity {

    @Id
    Long id;
    /** The reviewed book; {@code null} for a review whose book is unknown, so a sub-select over it sees a NULL. */
    Long bookId;
    Integer rating;

    protected ReviewEntity() {
    }

    public ReviewEntity(Long id, Long bookId, Integer rating) {
        this.id = id;
        this.bookId = bookId;
        this.rating = rating;
    }

    public Long id() {
        return id;
    }

    public Long bookId() {
        return bookId;
    }

    public Integer rating() {
        return rating;
    }
}
