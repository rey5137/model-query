package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * The parent a delete {@code throughEntities()} removes with its children (R-WRT-43): the {@code parent} children by
 * {@code cascade = REMOVE}, the {@code holder} children by {@code orphanRemoval}.
 */
@Entity
@Table(name = "ins_parent")
public class InsParentEntity {
    @Id
    Long id;

    String name;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.REMOVE)
    List<InsChildEntity> children = new ArrayList<>();

    @OneToMany(mappedBy = "holder", orphanRemoval = true)
    List<InsChildEntity> held = new ArrayList<>();
}
