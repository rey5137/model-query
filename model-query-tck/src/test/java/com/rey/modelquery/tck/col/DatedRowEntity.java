package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.util.Date;

/**
 * Fixture entity over {@code dated_rows} whose attributes are all {@link Date}, as in a code base written before
 * {@code java.time}: one per {@link TemporalType}, the timestamp one without {@code @Temporal} (AC-COL-28, D-122).
 */
@Entity
@Table(name = "dated_rows")
public class DatedRowEntity {
    @Id
    Long id;

    @Temporal(TemporalType.DATE)
    @Column(name = "born_on")
    Date bornOn;

    @Temporal(TemporalType.TIME)
    @Column(name = "rings_at")
    Date ringsAt;

    @Column(name = "logged_at")
    Date loggedAt;
}
