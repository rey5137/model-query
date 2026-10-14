package com.rey.modelquery.tck.arch.fixture.jpa;

/** A public jpa type with a non-public member type, which portability may not depend on. */
public class JpaWithProtectedPart {

    /** Not public: reachable only from a subclass. */
    protected static class Part {}
}
