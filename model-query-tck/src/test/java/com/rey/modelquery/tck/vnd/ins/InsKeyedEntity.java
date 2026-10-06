package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.tck.col.KeysetTypeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.NaturalId;

/**
 * An assigned-id root whose unique keys the mapping declares every other way: a {@code @NaturalId}, a two-column
 * {@code @Table(uniqueConstraints)}, one of whose columns is renamed, and a to-one's
 * {@code @JoinColumn(unique = true)}; {@code name} is unique nowhere (R-WRT-34). {@code shape} is a nullable
 * converted value class.
 */
@Entity
@Table(name = "ins_keyed", uniqueConstraints = @UniqueConstraint(columnNames = {"region", "ref_no"}))
public class InsKeyedEntity {
    @Id
    Long id;

    @NaturalId
    String code;

    String region;

    @Column(name = "ref_no")
    String ref;

    String name;

    @ManyToOne
    @JoinColumn(name = "owner_id", unique = true)
    InsAssignedEntity owner;

    @Convert(converter = KeysetTypeEntity.ShapeConverter.class)
    KeysetTypeEntity.Shape shape;
}
