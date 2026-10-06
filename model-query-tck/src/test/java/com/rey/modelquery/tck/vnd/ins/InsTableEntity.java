package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;

/** A table-generator root (increment 20), for the D-116 generator probes. */
@Entity
@Table(name = "ins_table")
public class InsTableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "ins_table_gen")
    @TableGenerator(name = "ins_table_gen", table = "ins_id_table", allocationSize = 20)
    Long id;

    @Column(unique = true)
    String code;

    String name;
}
