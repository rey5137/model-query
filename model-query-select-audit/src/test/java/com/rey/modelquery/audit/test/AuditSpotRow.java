package com.rey.modelquery.audit.test;

import com.rey.modelquery.annotations.ElementModel;

@ElementModel(AuditSpot.class)
public record AuditSpotRow(String city) {}
