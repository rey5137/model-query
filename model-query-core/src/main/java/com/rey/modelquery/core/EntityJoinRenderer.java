package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import java.util.function.Function;

/**
 * The provider's entity join, attached to the statement's root (R-VND-32, INV-7).
 *
 * @implSpec R-SRC-24, R-VND-32, D-193
 */
@EngineFacing
@Incubating
public interface EntityJoinRenderer {

    /**
     * Joins {@code entity} to the root {@code anchor}, calling {@code on} and applying its predicate exactly once
     * (R-SRC-24). A provider unable to attach to that root refuses with {@code MQ4014}.
     */
    @Incubating
    <E> From<?, E> join(From<?, ?> anchor, Class<E> entity, JoinKind kind, Function<From<?, E>, Predicate> on);
}
