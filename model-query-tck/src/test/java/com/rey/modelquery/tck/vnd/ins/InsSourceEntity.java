package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The rows an insert-select probe reads (D-116): an assigned id, a code and a name. */
@Entity
@Table(name = "ins_source")
public class InsSourceEntity {
    @Id
    Long id;

    String code;

    String name;
}
