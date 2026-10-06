package com.rey.modelquery.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Test entity for {@link KeysetCursorTest}: the byte[] order key of R-PAG-17, compared as an expression. */
@Entity
@Table(name = "keyset_cursor_rows")
public class KeysetCursorEntity {
    @Id
    Long id;

    @Column(name = "payload")
    byte[] payload;

    protected KeysetCursorEntity() {}

    KeysetCursorEntity(long id, byte[] payload) {
        this.id = id;
        this.payload = payload;
    }
}
