package com.rey.modelquery.tck.harness;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A test that runs once per selected {@link TckTarget}. Declare a {@link TckDatabase} parameter to receive the
 * started, seeded database. Name the method after the criterion it covers (R-QA-05).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TestTemplate
@ExtendWith(TckExtension.class)
public @interface TckTest {}
