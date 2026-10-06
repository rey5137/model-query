package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;

/**
 * How the provider generates a root entity's id, as an {@link InsertSupport} reports it: the engine decides from it
 * which inserts it can write and whether it draws the keys first (api/14 R-WRT-26, R-VND-14, D-117).
 *
 * @implSpec R-VND-14, D-116, D-117
 */
@Incubating
public sealed interface IdGeneration {

    /** No generator: each row carries its id. */
    record Assigned() implements IdGeneration {}

    /** An identity column the database fills on insert. */
    record Identity() implements IdGeneration {}

    /**
     * A sequence generator.
     *
     * @param physical whether a database sequence backs it, rather than a table emulating one
     * @param increment the optimizer's increment: 1 draws one key per round trip, more draws a pooled block
     */
    record Sequence(boolean physical, int increment) implements IdGeneration {}

    /** A table generator. */
    record Table() implements IdGeneration {}

    /** A UUID generator. */
    record Uuid() implements IdGeneration {}

    /**
     * Any other generator.
     *
     * @param generatorClass the generator's class name, for the message that refuses it
     */
    record Other(String generatorClass) implements IdGeneration {

        public Other {
            Objects.requireNonNull(generatorClass, "generatorClass");
        }
    }
}
