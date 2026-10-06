package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;

/** A root with a {@code @SecondaryTable}, which no bulk insert writes (R-WRT-26, D-116). */
@Entity
@Table(name = "ins_secondary")
@SecondaryTable(name = "ins_secondary_extra")
public class InsSecondaryEntity {
    @Id
    Long id;
    String name;
    @Column(table = "ins_secondary_extra")
    String extra;
}
