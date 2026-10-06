package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** The subclass of {@link InsJoinedEntity}, on a table of its own. */
@Entity
@Table(name = "ins_joined_child")
public class InsJoinedChildEntity extends InsJoinedEntity {
    String detail;
}
