package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.ElementModel;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.SpotValue;
import java.math.BigDecimal;

@ElementModel(SpotValue.class)
public record SpotRow(String city, @Column(attribute = "geo.lat") BigDecimal lat, ConstantStatus status) {}
