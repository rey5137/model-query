package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Attachment;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Invoice;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
import org.hibernate.SessionFactory;

final class ColumnJoinProbes {
    private ColumnJoinProbes() {}

    static void rendering(SpikeHarness harness, SessionFactory sessions) {
        for (Q.Anchor anchor : Q.Anchor.values()) {
            for (JoinKind join : JoinKind.values()) {
                for (Q.Kind kind : Q.Kind.values()) {
                    var options = new Q.Options(anchor, join, Q.Target.SIMPLE, false);
                    compare(harness, sessions, anchor + "/" + join + "/" + kind, options, kind);
                }
            }
        }
        for (Q.Target target : List.of(Q.Target.ID_CLASS, Q.Target.EMBEDDED)) {
            for (JoinKind join : JoinKind.values()) {
                compare(harness, sessions, target + "/" + join,
                        new Q.Options(Q.Anchor.ROOT, join, target, false), Q.Kind.STREAM);
            }
        }
        compare(harness, sessions, "left-join-filter",
                new Q.Options(Q.Anchor.ROOT, JoinKind.LEFT, Q.Target.SIMPLE, true), Q.Kind.STREAM);
        harness.cell("B.1", "orphan-entity-join", () -> {
            List<String> rows = Q.run(sessions, Q.Options.defaults(), Q.Kind.ONE);
            Q.equal(rows, List.of("50|INV-50|null"));
            return rows.toString();
        });
        harness.cell("B.1", "orphan-read-only-association-em-find", () -> SpikeHarness.inEm(sessions, em -> {
            Invoice invoice = em.find(Invoice.class, 50L);
            try {
                org.hibernate.Hibernate.initialize(invoice.rep);
            } catch (EntityNotFoundException expected) {
                return "em.find(Invoice,50) succeeds; initializing rep throws " + SpikeHarness.error(expected);
            }
            throw new SpikeHarness.Mismatch("read-only association did not throw EntityNotFoundException");
        }));
        harness.notRun("B.1", "treated-path-engine-dispatch",
                "No treated/inherited invoice mapping; ROOT_ID_COUNT tests its count-distinct SQL shape only.");
    }

    private static void compare(SpikeHarness harness, SessionFactory sessions, String name,
            Q.Options options, Q.Kind kind) {
        harness.cell("B.1", name, () -> {
            List<String> actual = Q.run(sessions, options, kind);
            Q.equal(actual, Q.nativeRows(harness, Q.nativeInvoice(options, kind)));
            String sql = harness.lastStatement().sql().toLowerCase(Locale.ROOT);
            SpikeHarness.expect(sql.contains("join employee"), "entity join missing: " + sql);
            if (options.anchor() != Q.Anchor.ROOT) {
                int pathAt = sql.indexOf("join region");
                SpikeHarness.expect(pathAt >= 0 && pathAt < sql.indexOf("join employee"),
                        "anchor path was created after entity join: " + sql);
            }
            return "rows=" + actual.size() + "; sample=" + actual.stream().limit(2).toList();
        });
    }

    static void staticTerm(SpikeHarness harness, SessionFactory sessions) {
        for (boolean when : List.of(false, true)) {
            harness.cell("B.4", "attachment-when=" + when, () -> {
                var renderer = Q.provider(sessions).entityJoins().orElseThrow();
                List<String> actual = SpikeHarness.inEm(sessions, em -> {
                    var cb = em.getCriteriaBuilder();
                    var query = cb.createTupleQuery();
                    var root = query.from(Attachment.class);
                    var type = cb.parameter(String.class, "ownerType");
                    var invoice = renderer.join(root, Invoice.class, JoinKind.LEFT, joined -> {
                        var id = cb.equal(joined.get("id"), root.get("ownerId"));
                        return when ? cb.and(id, cb.equal(root.get("ownerType"), type)) : id;
                    });
                    query.multiselect(root.get("id"), root.get("ownerType"), invoice.get("number"));
                    query.orderBy(cb.asc(root.get("id")));
                    var executable = em.createQuery(query).setHint("jakarta.persistence.query.timeout", 60_000);
                    if (when) {
                        executable.setParameter("ownerType", "INVOICE");
                    }
                    List<String> first = Q.rows(executable.getResultList());
                    Q.equal(Q.rows(executable.getResultList()), first);
                    return first;
                });
                String sql = "select a.id,a.owner_type,i.invoice_number from attachment a left join invoice i "
                        + "on i.id=a.owner_id" + (when ? " and a.owner_type='INVOICE'" : "") + " order by a.id";
                Q.equal(actual, Q.nativeRows(harness, sql));
                long wrong = actual.stream().filter(row -> row.contains("|TICKET|INV-")).count();
                SpikeHarness.expect(wrong == (when ? 0 : 100), "colliding-id wrong rows=" + wrong);
                if (when) {
                    for (var statement : harness.statements()) {
                        SpikeHarness.expect(statement.binds().equals("INVOICE"),
                                "static term must bind exactly once: " + statement);
                    }
                }
                SpikeHarness.expect(harness.statements().size() == 2, "duplicate execution must issue two statements");
                SpikeHarness.expect(harness.statements().get(0).sql().equals(harness.statements().get(1).sql()),
                        "SQL changed on duplicate execution");
                return "rows=" + actual.size() + "; wrong-owner rows=" + wrong + "; duplicate rows/SQL identical";
            });
        }
    }
}
