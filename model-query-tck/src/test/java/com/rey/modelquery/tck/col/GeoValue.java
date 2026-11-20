package com.rey.modelquery.tck.col;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** A nested element attribute. */
@Embeddable
public class GeoValue {
    BigDecimal lat;
}
