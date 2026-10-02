package com.rey.modelquery.tck.col;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.List;

/**
 * Fixture entity over {@code labels}, many-to-many with the orders: owned on this side, which the label child of an
 * order crosses in its foreign key, and mapped back by {@code OrderEntity.labels}.
 */
@Entity
@Table(name = "labels")
public class LabelEntity {
    @Id
    Long id;

    String name;

    @ManyToMany
    @JoinTable(name = "order_labels", joinColumns = @JoinColumn(name = "label_id"),
            inverseJoinColumns = @JoinColumn(name = "order_id"))
    List<OrderEntity> orders;
}
