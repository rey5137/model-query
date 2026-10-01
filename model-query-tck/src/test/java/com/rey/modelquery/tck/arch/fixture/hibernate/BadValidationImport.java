package com.rey.modelquery.tck.arch.fixture.hibernate;

/** Violates: only @ValidChanges and its validator import jakarta.validation. */
public class BadValidationImport {
    jakarta.validation.fixturestub.Stub validation;
}
