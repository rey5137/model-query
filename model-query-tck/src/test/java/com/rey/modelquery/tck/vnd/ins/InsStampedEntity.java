package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An assigned-id root with a database default beside a constructor value ({@code status}) and a timestamp the server
 * or the caller sets ({@code createdAt}): the target of {@code @ExcludeFromInserts} (R-PROC-27, D-125).
 */
@Entity
@Table(name = "ins_stamped")
public class InsStampedEntity {
    @Id
    Long id;

    String customer;

    @Column(columnDefinition = "varchar(20) default 'NEW'")
    String status = "CTOR";

    Instant createdAt;
}
