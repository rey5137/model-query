package com.rey.modelquery.tck.arch.fixture.test;

/** Violates: test depends only on core, annotations and AssertJ; javax.inject is third-party. */
public class BadTestThirdParty {
    javax.inject.fixturestub.Stub inject;
}
