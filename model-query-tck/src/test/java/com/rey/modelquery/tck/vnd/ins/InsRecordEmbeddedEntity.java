package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A root with a record embeddable, which {@code persist} cannot instantiate and set (R-WRT-39, MQ1805). */
@Entity
@Table(name = "ins_record_embedded")
public class InsRecordEmbeddedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Embedded
    Point point;

    /** A constructor-only embeddable. */
    @Embeddable
    public record Point(Integer x, Integer y) {}
}
