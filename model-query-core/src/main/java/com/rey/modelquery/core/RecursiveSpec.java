package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.List;
import java.util.Objects;

/**
 * A recursive source's CTE at render time (R-SRC-07, R-SRC-12): the seed the source's own rows come from,
 * the {@link RecursiveTerm} that steps one level deeper, the depth limit, and the CTE column names the provider needs
 * to cycle on and to build the two checks of {@link RecursiveCte}. Built by {@link SourceQuery} per statement and
 * handed to {@code SourceRenderer#recursive}, which registers it as a {@code WITH RECURSIVE} CTE.
 *
 * <p>The seed is level 0 for every row it holds. The term recurses while the parent's level is below
 * {@code maxDepth + 1}, so the CTE holds every row up to and including that extra level, which is what makes a tree
 * deeper than {@code maxDepth} visible to the depth check. Engine-facing, and immutable.
 *
 * @param cb the builder the seed and the term are built with
 * @param seed the CTE's seed: the source's fields, its {@code @Depth} column the literal 0, filtered by the source's
 *     {@code where}
 * @param term the recursive term: one level deeper, each row joined to its parent by the step's keys
 * @param keyType the Java type of the source key's first column, which the checks are selected as
 * @param keyAliases the CTE column aliases of the source key's columns, in key order, which the provider cycles on
 * @param levelAlias the CTE column alias of the level: the {@code @Depth} field's name
 * @param markAlias the CTE column alias of the cycle mark, which the provider sets and the outer query filters on
 * @param maxDepth the deepest level the outer query returns, the seed rows being level 0
 * @implSpec R-SRC-07, R-SRC-12, R-PROC-37
 */
@EngineFacing
@Incubating
public record RecursiveSpec(CriteriaBuilder cb, CriteriaQuery<Tuple> seed, RecursiveTerm term, Class<?> keyType,
        List<String> keyAliases, String levelAlias, String markAlias, int maxDepth) {

    /** Validates the components. */
    public RecursiveSpec {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(keyType, "keyType");
        keyAliases = List.copyOf(keyAliases);
        Objects.requireNonNull(levelAlias, "levelAlias");
        Objects.requireNonNull(markAlias, "markAlias");
        if (keyAliases.isEmpty()) {
            throw new IllegalArgumentException("a recursive source has at least one key column");
        }
    }
}
