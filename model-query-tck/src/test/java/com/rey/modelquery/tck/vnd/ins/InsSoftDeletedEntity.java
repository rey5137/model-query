package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;

/** A root whose mapping's {@code @SQLDelete} marks the row deleted instead of deleting it (R-WRT-43). */
@Entity
@Table(name = "ins_soft_deleted")
@SQLDelete(sql = "update ins_soft_deleted set deleted = true where id = ?")
public class InsSoftDeletedEntity {
    @Id
    Long id;

    String name;

    boolean deleted;
}
