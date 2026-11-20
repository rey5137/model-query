package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/** An element with a nested embeddable and a converted attribute. */
@Embeddable
public class SpotValue {
    String city;

    @Embedded
    GeoValue geo;

    @Column(name = "state")
    @Convert(converter = StatusCodeConverter.class)
    ConstantStatus status;
}
