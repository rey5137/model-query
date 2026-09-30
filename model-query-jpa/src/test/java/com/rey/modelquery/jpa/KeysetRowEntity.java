package com.rey.modelquery.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Test entity for {@link KeysetTest}: two nullable ordering columns and one non-null one, all with duplicates. */
@Entity
@Table(name = "keyset_rows")
public class KeysetRowEntity {
    @Id
    Long id;

    @Column(name = "a")
    Integer a;

    @Column(name = "b")
    Integer b;

    @Column(name = "c", nullable = false)
    Integer c;

    protected KeysetRowEntity() {}

    KeysetRowEntity(long id, Integer a, Integer b, int c) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.c = c;
    }
}
