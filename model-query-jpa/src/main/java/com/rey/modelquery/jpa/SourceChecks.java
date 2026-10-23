package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import jakarta.persistence.Tuple;
import java.util.List;

/**
 * The one check site every {@code Tuple} read over a {@link BuiltQuery} goes through: the statement's own first row
 * carries the checks {@code core} selected beside its fields, and this reads them before the read returns a row
 * (R-SRC-08, R-SRC-15, AC-SRC-25). {@code DefaultModelQueryExecutor}'s {@code rows}, {@code single} and
 * {@code resultStream} overloads taking a {@code BuiltQuery}, and {@code KeysetWrite}'s key select, all call it, so a
 * read over a source has no unchecked signature to reach (D-147). A statement over no recursive source carries no
 * check columns, and an empty result carries no row to read.
 */
final class SourceChecks {

    private SourceChecks() {}

    /**
     * Refuses depth ({@code MQ2701}), then cycle ({@code MQ2702}), then a tree's duplicate key ({@code MQ2703}),
     * from the check columns of the statement's own first row (R-SRC-14), so no
     * too-deep or doubled row reaches the mapper or a sink (R-SRC-07). A statement over no recursive source carries
     * no check columns, and an empty result carries no row to read: nothing is refused then, which is the race
     * {@code engine/21} and RFC 0013 §5 leave documented under READ COMMITTED.
     *
     * @throws ModelQueryExecutionException {@code MQ2701}, {@code MQ2702} or {@code MQ2703}, naming the model, the
     *     source join whose walk it is, if any, and the first offending key
     */
    static void checkRecursion(Object label, BuiltQuery<?> built, List<Tuple> tuples) {
        if (built.recursionChecks().isEmpty() || tuples.isEmpty()) {
            return;
        }
        Tuple first = tuples.get(0);
        // A joined walk's message names the join that reads it (D-187).
        String where = built.recursionJoin().map(join -> label + ": " + join).orElseGet(() -> String.valueOf(label));
        Object deepest = first.get(BuiltQuery.RECURSION_DEPTH);
        if (deepest != null) {
            throw new ModelQueryExecutionException(MqCode.MQ2701, where + ": the source's tree is deeper than its "
                    + "maxDepth(" + built.sourceMaxDepth() + "): key " + deepest
                    + " is one level past the limit; raise maxDepth or narrow the source's where(...)");
        }
        Object cycle = first.get(BuiltQuery.RECURSION_CYCLE);
        if (cycle != null) {
            throw new ModelQueryExecutionException(MqCode.MQ2702, where + ": the source's graph has a cycle: key "
                    + cycle + " is reachable by two paths, which a source reads twice; remove the cycle or filter it "
                    + "out of the source's where(...)");
        }
        if (built.recursionChecks().stream()
                .anyMatch(check -> BuiltQuery.RECURSION_DUPLICATE_KEY.equals(check.getAlias()))) {
            Object duplicate = first.get(BuiltQuery.RECURSION_DUPLICATE_KEY);
            if (duplicate != null) {
                String keyNote = built.sourceKeyColumns().size() > 1 ? " (only the key's first column is shown)" : "";
                throw new ModelQueryExecutionException(MqCode.MQ2703, where + ": the tree source reaches key "
                        + built.sourceKeyColumns().get(0) + "=" + duplicate + " twice" + keyNote
                        + "; narrow the seeds or use recursive(...) "
                        + "for a graph that reaches a node by more than one path");
            }
        }
    }
}
