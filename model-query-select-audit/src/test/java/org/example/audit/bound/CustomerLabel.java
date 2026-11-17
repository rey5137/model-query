package org.example.audit.bound;

import com.rey.modelquery.annotations.PrimaryKey;

public record CustomerLabel(@PrimaryKey Long id, String name) {}
