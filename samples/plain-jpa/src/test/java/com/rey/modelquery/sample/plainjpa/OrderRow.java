package com.rey.modelquery.sample.plainjpa;

import java.math.BigDecimal;

/** The DTO a JPQL constructor expression fills, for the migration recipe. */
public record OrderRow(Long id, String status, BigDecimal total, String customerName) {}
