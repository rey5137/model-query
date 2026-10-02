package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Fixture entity over {@code customer_notes}, keyed on a customer's email under a case-insensitive collation. */
@Entity
@Table(name = "customer_notes")
public class CustomerNoteEntity {
    @Id
    Long id;

    @Column(name = "customer_email")
    String customerEmail;

    String body;
}
