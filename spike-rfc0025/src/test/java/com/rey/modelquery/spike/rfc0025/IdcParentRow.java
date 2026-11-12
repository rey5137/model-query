package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/**
 * An {@code @IdClass} parent: a child keyed on one id attribute. Two key paths, or a default to a two-attribute
 * {@code @PrimaryKey}, is MQ3404, and {@code ChildField#key()} holds one column, so no composite child can be declared.
 */
@QueryModel(root = Entities.IdcParent.class)
public record IdcParentRow(@PrimaryKey String region, @PrimaryKey Long no, String name,
        @Child(key = "no", foreignKey = "parentNo") List<IdcChildRow> byNo) {}
