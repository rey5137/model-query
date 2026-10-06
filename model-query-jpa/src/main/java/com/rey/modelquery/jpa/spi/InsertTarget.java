package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;
import java.util.Objects;

/**
 * What an {@link InsertSupport} reports of an insert's root entity (R-VND-14, D-117).
 *
 * @param id how the provider generates the root's id
 * @param unsupportedMappings each mapping of the root that no bulk insert writes, such as {@code JOINED} inheritance,
 *     a {@code @SecondaryTable}, a composite id with generated parts or {@code @MapsId}, described for the message
 *     that refuses it ({@code MQ1805}); empty when there is none
 * @implSpec R-VND-14, D-116, D-117
 */
@Incubating
public record InsertTarget(IdGeneration id, List<String> unsupportedMappings) {

    public InsertTarget {
        Objects.requireNonNull(id, "id");
        unsupportedMappings = List.copyOf(unsupportedMappings);
    }
}
