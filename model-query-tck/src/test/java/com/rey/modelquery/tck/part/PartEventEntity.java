package com.rey.modelquery.tck.part;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Fixture entity over {@code part_events}, a table partitioned by month on {@code created_at}. The JPA id is
 * {@code id} alone; the DDL primary key is {@code (id, created_at)}, as MySQL needs the partition column in every
 * unique key.
 */
@Entity
@Table(name = "part_events")
public class PartEventEntity {
    @Id
    Long id;

    @ManyToOne
    @JoinColumn(name = "order_id")
    PartOrderEntity order;

    @Column(name = "created_at")
    LocalDateTime createdAt;

    String payload;
}
