package com.rey.modelquery.tck.arch.fixture.jpa;

/** Violates: only @ValidChanges and its validator import jakarta.validation, not the rest of jpa. */
public class BadJpaValidationImport {
    jakarta.validation.fixturestub.Stub validation;
}
