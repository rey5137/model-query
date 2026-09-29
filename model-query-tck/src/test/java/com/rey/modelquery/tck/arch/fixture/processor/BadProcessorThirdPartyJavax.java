package com.rey.modelquery.tck.arch.fixture.processor;

/** Violates: processor may use only the JDK's javax packages; javax.inject is third-party. */
public class BadProcessorThirdPartyJavax {
    javax.inject.fixturestub.Stub inject;
}
