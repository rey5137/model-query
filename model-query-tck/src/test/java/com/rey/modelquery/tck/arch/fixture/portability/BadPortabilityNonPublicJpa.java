package com.rey.modelquery.tck.arch.fixture.portability;

import com.rey.modelquery.tck.arch.fixture.jpa.JpaWithProtectedPart;

/** Violates: portability depends on the public types of jpa only, never a non-public one. */
public class BadPortabilityNonPublicJpa extends JpaWithProtectedPart {
    Part part;
}
