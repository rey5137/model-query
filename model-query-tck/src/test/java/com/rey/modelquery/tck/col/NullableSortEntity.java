package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Fixture entity over {@code nullable_sort_rows}: nullable sort columns with duplicates. */
@Entity
@Table(name = "nullable_sort_rows")
public class NullableSortEntity {
    @Id
    Long id;

    @Column(name = "sort_int")
    Integer sortInt;

    @Column(name = "sort_text")
    String sortText;

    @Column(name = "sort_ts")
    LocalDateTime sortTs;
}
