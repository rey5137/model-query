package com.rey.modelquery.spike.rfc0021;

import static com.rey.modelquery.spike.rfc0021.PartAProbes.eq;
import static com.rey.modelquery.spike.rfc0021.SpikeHarness.expect;

import com.rey.modelquery.spike.rfc0021.Q.P;
import com.rey.modelquery.spike.rfc0021.Q.Sel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * A.5 parity: the three motivating variants (Details, Parents, Simple) against the native SQL's rows, with
 * include_deleted 0 and 1 and each optional filter present and absent. The library side is Criteria carrying the
 * proposed entity join and the Part B join filters (deleted profiles blanked in the ON).
 */
final class ParityProbes {

    private ParityProbes() {}

    private static final Map<String, Consumer<P>> FILTERS = new LinkedHashMap<>();

    static {
        FILTERS.put("no filter", p -> {});
        FILTERS.put("hierarchy 1", p -> p.hierarchy = 1L);
        FILTERS.put("hierarchy 1 + manager 2", p -> {
            p.hierarchy = 1L;
            p.manager = 2L;
        });
        FILTERS.put("node 14", p -> p.node = 14L);
        FILTERS.put("first name P0014", p -> p.firstName = "P0014");
        FILTERS.put("all four", p -> {
            p.hierarchy = 1L;
            p.manager = 2L;
            p.node = 14L;
            p.firstName = "P0014";
        });
    }

    static void run(SpikeHarness h, SessionFactory sf) {
        List<Long> nodeIds = new ArrayList<>();
        for (long k = 1; k <= 300; k++) {
            nodeIds.add(k);
        }
        for (boolean incl : new boolean[] {false, true}) {
            for (var f : FILTERS.entrySet()) {
                String tag = "include_deleted=" + (incl ? 1 : 0) + ", " + f.getKey();
                P details = PartAProbes.details().with(p -> {
                    p.incl = incl;
                    f.getValue().accept(p);
                });
                h.cell("A.5/details", tag, () -> {
                    List<String> got = Q.run(sf, details, Sel.DETAILS, 0, 0);
                    List<String> want = Q.nativeRows(sf, Q.nativeDetails(details, false));
                    eq("details", got, want);
                    long count = Long.parseLong(Q.run(sf, details, Sel.COUNT, 0, 0).get(0));
                    long nativeCount = Long.parseLong(Q.nativeRows(sf, Q.nativeDetails(details, true)).get(0));
                    expect(count == nativeCount && count == want.size(), "count " + count + " native " + nativeCount);
                    List<String> page = Q.run(sf, details, Sel.DETAILS, 0, 20);
                    eq("page of 20", page, want.subList(0, Math.min(20, want.size())));
                    return "rows=" + got.size() + ", count=" + count + ", page of 20 equal; all equal to native";
                });
                P parents = details.with(p -> {
                    p.source = SqmJoinType.INNER;
                    p.nodeIds = nodeIds;
                });
                h.cell("A.5/parents", tag, () -> {
                    List<String> got = Q.run(sf, parents, Sel.DETAILS, 0, 0);
                    List<String> want = Q.nativeRows(sf, Q.nativeDetails(parents, false));
                    eq("parents", got, want);
                    long count = Long.parseLong(Q.run(sf, parents, Sel.COUNT, 0, 0).get(0));
                    expect(count == want.size(), "count " + count + " vs " + want.size());
                    return "rows=" + got.size() + ", count=" + count + ", equal to native";
                });
                P simple = new P();
                simple.incl = incl;
                simple.joinFilters = true;
                simple.skipSource = true;
                simple.hierarchy = details.hierarchy;
                simple.node = details.node;
                simple.firstName = details.firstName;
                h.cell("A.5/simple", tag + (details.manager != null ? " (manager n/a: no source)" : ""), () -> {
                    List<String> got = Q.run(sf, simple, Sel.SIMPLE, 0, 0);
                    List<String> want = Q.nativeRows(sf, Q.nativeSimple(simple));
                    eq("simple", got, want);
                    long count = Long.parseLong(Q.run(sf, simple, Sel.COUNT, 0, 0).get(0));
                    expect(count == want.size(), "count(hn)=" + count + " vs distinct " + want.size()
                            + " ((hierarchy_id, node_id) unique in this data)");
                    return "rows=" + got.size() + ", count(hn)=" + count + " equals native count(distinct)";
                });
            }
        }
        withoutPartB(h, sf);
    }

    /** A.7 item 2: without Part B the deleted checks on c and p have no ON form. */
    private static void withoutPartB(SpikeHarness h, SessionFactory sf) {
        P nativeP = PartAProbes.details().with(p -> p.hierarchy = 1L);
        h.cell("A.5/no-part-B", "include_deleted=0: no deleted check on c and p (what the library could do) vs native", () -> {
            List<String> want = Q.nativeRows(sf, Q.nativeDetails(nativeP, false));
            List<String> got = Q.run(sf, nativeP.with(p -> p.joinFilters = false), Sel.DETAILS, 0, 0);
            expect(got.size() == want.size(), "rows differ in number: " + got.size() + " vs " + want.size());
            long different = 0;
            for (int i = 0; i < got.size(); i++) {
                if (!got.get(i).equals(want.get(i))) {
                    different++;
                }
            }
            return "rows=" + got.size() + " (same nodes), rows that differ from native=" + different
                    + " (deleted node or manager profile read instead of blanked)";
        });
        h.cell("A.5/no-part-B", "include_deleted=0: deleted checks of c and p as where terms vs native", () -> {
            List<String> want = Q.nativeRows(sf, Q.nativeDetails(nativeP, false));
            List<String> got = Q.run(sf, nativeP.with(p -> {
                p.joinFilters = false;
                p.whereDeleted = true;
            }), Sel.DETAILS, 0, 0);
            return "rows=" + got.size() + " vs native " + want.size() + " (" + (want.size() - got.size())
                    + " nodes dropped where native blanks)";
        });
    }
}
