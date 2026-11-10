package com.rey.modelquery.tck.part;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Fixture entity over {@code part_orders}, the parent of the partitioned {@code part_events} (R-FCH-18). */
@Entity
@Table(name = "part_orders")
public class PartOrderEntity {
    @Id
    Long id;

    @Column(name = "placed_at")
    LocalDateTime placedAt;
}
