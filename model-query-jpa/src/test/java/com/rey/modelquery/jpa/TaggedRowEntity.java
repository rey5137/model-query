package com.rey.modelquery.jpa;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/** Test entity for {@link ChildStatementsTest}'s value runs: a row, its tags, an element collection,
 * and the rows it parents. */
@Entity
@Table(name = "tagged_rows")
public class TaggedRowEntity {
    @Id
    Long id;

    @ManyToOne
    @JoinColumn(name = "parent_id")
    TaggedRowEntity parent;

    // A collection a value load's path may not cross (MQ1713).
    @OneToMany(mappedBy = "parent")
    List<TaggedRowEntity> children = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "tagged_row_tags", joinColumns = @JoinColumn(name = "row_id"))
    @Column(name = "tag")
    List<String> tags = new ArrayList<>();

    protected TaggedRowEntity() {}

    TaggedRowEntity(long id, String... tags) {
        this.id = id;
        this.tags.addAll(List.of(tags));
    }
}
