package com.rey.modelquery.tck.col;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;

/**
 * Fixture entity over {@code tagged_cards}, whose element collections a value child reads: basic tags, states
 * stored through a converter, and scores in their {@code @OrderColumn}, each allowing repeats and {@code NULL}s
 * (TCK AC-FCH-30 to AC-FCH-32).
 */
@Entity
@Table(name = "tagged_cards")
public class TaggedCardEntity {
    @Id
    Long id;

    String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holder_id")
    TaggedCardEntity holder;

    // The inverse of holder: a collection a value child's path may not cross (MQ3406, MQ1713).
    @OneToMany(mappedBy = "holder")
    List<TaggedCardEntity> held;

    @ElementCollection
    @CollectionTable(name = "tagged_card_tags", joinColumns = @JoinColumn(name = "card_id"))
    @Column(name = "tag")
    List<String> tags;

    @ElementCollection
    @CollectionTable(name = "tagged_card_states", joinColumns = @JoinColumn(name = "card_id"))
    @Column(name = "state")
    @Convert(converter = StatusCodeConverter.class)
    List<ConstantStatus> states;

    @ElementCollection
    @CollectionTable(name = "tagged_card_scores", joinColumns = @JoinColumn(name = "card_id"))
    @OrderColumn(name = "pos")
    @Column(name = "score")
    List<Integer> scores;

    @ElementCollection
    @CollectionTable(name = "tagged_card_spots", joinColumns = @JoinColumn(name = "card_id"))
    List<SpotValue> spots;

    @ElementCollection
    @CollectionTable(name = "tagged_card_ordered_spots", joinColumns = @JoinColumn(name = "card_id"))
    @OrderColumn(name = "pos")
    List<SpotValue> orderedSpots;

    @ElementCollection
    @CollectionTable(name = "tagged_card_labels", joinColumns = @JoinColumn(name = "card_id"))
    @MapKeyColumn(name = "label_key")
    @Column(name = "label_val")
    Map<String, String> labels;

    @ElementCollection
    @CollectionTable(name = "tagged_card_counts", joinColumns = @JoinColumn(name = "card_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "status_key")
    @Column(name = "count_val")
    Map<OrderStatus, Integer> counts;

    @ElementCollection
    @CollectionTable(name = "tagged_card_spots_map", joinColumns = @JoinColumn(name = "card_id"))
    @MapKeyColumn(name = "spot_key")
    Map<String, SpotValue> spotsMap;

    @ElementCollection
    @CollectionTable(name = "tagged_card_case_labels", joinColumns = @JoinColumn(name = "card_id"))
    @MapKeyColumn(name = "label_key")
    @Convert(converter = LowerCaseConverter.class, attributeName = "key")
    @Column(name = "label_val")
    Map<String, String> caseLabels;

    @OneToMany
    @JoinTable(name = "tagged_card_held_map",
            joinColumns = @JoinColumn(name = "holder_id"),
            inverseJoinColumns = @JoinColumn(name = "card_id"))
    @MapKeyColumn(name = "role_key")
    Map<String, TaggedCardEntity> heldMap;
}
