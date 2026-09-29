package com.rey.modelquery.tck.arch.fixture.core;

/** Violates: core imports only the JDK; a third-party javax package is not the JDK. */
public class BadCoreThirdPartyJavax {
    javax.inject.fixturestub.Stub inject;
}
