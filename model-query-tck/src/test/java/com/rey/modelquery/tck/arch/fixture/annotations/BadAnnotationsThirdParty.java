package com.rey.modelquery.tck.arch.fixture.annotations;

/** Violates: annotations depends only on the JDK; javax.inject is third-party. */
public class BadAnnotationsThirdParty {
    javax.inject.fixturestub.Stub inject;
}
