package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Employee;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.EmployeeEmb;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.EmployeeIc;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Invoice;
import jakarta.persistence.criteria.Path;
import java.util.ArrayList;
import java.util.Collections;
import org.hibernate.SessionFactory;

final class GroupCountProbes {
    private GroupCountProbes() {}

    static void run(SpikeHarness h, SessionFactory sessions) {
        for (Q.Target target : Q.Target.values()) {
            for (JoinKind kind : JoinKind.values()) {
                h.cell("B.3", target + "/" + kind, () -> SpikeHarness.inEm(sessions, em -> {
                    var cb = em.getCriteriaBuilder();
                    var grouped = cb.createTupleQuery();
                    var root = grouped.from(Invoice.class);
                    Class<?> entity = switch (target) {
                        case SIMPLE -> Employee.class;
                        case ID_CLASS -> EmployeeIc.class;
                        case EMBEDDED -> EmployeeEmb.class;
                    };
                    var employee = Q.provider(sessions).entityJoins().orElseThrow().join(root, entity, kind, join -> {
                        Path<?> id = target == Q.Target.EMBEDDED ? join.get("id") : join;
                        var key = cb.equal(id.get("code"), root.get("repCode"));
                        return target == Q.Target.SIMPLE ? key
                                : cb.and(key, cb.equal(id.get("regionCode"), root.get("regionCode")));
                    });
                    grouped.multiselect(employee.get("name"), cb.count(root)).groupBy(employee.get("name"));
                    var groups = em.createQuery(grouped).setHint("jakarta.persistence.query.timeout", 60_000)
                            .getResultList();
                    var options = new Q.Options(Q.Anchor.ROOT, kind, target, false);
                    String detail = Q.nativeInvoice(options, Q.Kind.STREAM);
                    String from = detail.substring(detail.indexOf(" from "), detail.lastIndexOf(" order by "));
                    java.util.List<String> wanted;
                    try {
                        wanted = Q.nativeRows(h, "select e.name, count(*)" + from + " group by e.name");
                    } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException("native grouped query failed", failure);
                    }
                    var actual = new ArrayList<>(Q.rows(groups));
                    Collections.sort(actual);
                    Collections.sort(wanted);
                    Q.equal(actual, wanted);
                    var count = Q.provider(sessions).countQuery(grouped).orElseThrow();
                    long got = em.createQuery(count).setHint("jakarta.persistence.query.timeout", 60_000)
                            .getSingleResult();
                    SpikeHarness.expect(h.lastStatement().sql().toLowerCase().contains("join employee"),
                            "count copy dropped employee join");
                    SpikeHarness.expect(got == wanted.size(), "groups=" + wanted.size() + ", copied count=" + got);
                    return "copy retains column-anchored join; groups=" + got;
                }));
            }
        }
    }
}
