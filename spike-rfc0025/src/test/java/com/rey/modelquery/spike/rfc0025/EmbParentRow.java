package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/**
 * An {@code @EmbeddedId} parent: a child keyed on one component of the id. Keyed on the whole id ({@code key = "id"})
 * the processor refuses it (MQ3404, an embedded value), so {@link HandChildren} declares that child by hand.
 */
@QueryModel(root = Entities.EmbParent.class)
public record EmbParentRow(@PrimaryKey @Column(attribute = "id.region") String region,
        @PrimaryKey @Column(attribute = "id.no") Long no, @Column(attribute = "id") Entities.EmbKey id, String name,
        @Child(key = "id.region", foreignKey = "parent.region") List<EmbChildRow> byRegion) {}
