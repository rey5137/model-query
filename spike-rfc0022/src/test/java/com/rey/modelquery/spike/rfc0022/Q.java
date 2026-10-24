package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.EntityJoinRenderer;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Employee;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.EmployeeEmb;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.EmployeeIc;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Invoice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;

/** Criteria emulation of B.3's proposed creation step, using the existing production entity-join renderer. */
final class Q {
    enum Anchor { ROOT, LEFT_PATH, INNER_PATH }
    enum Target { SIMPLE, ID_CLASS, EMBEDDED }
    enum Kind {
        CONTENT, COUNT, ROOT_ID_COUNT, PK_FIRST, PK_SECOND, KEYSET, BY_KEYS, ONE, EXPORT_FIRST, EXPORT_NEXT,
        STREAM, FETCH_PARENT
    }
    record Options(Anchor anchor, JoinKind join, Target target, boolean filter) {
        static Options defaults() { return new Options(Anchor.ROOT, JoinKind.LEFT, Target.SIMPLE, false); }
    }
    private Q() {}

    static ProviderSupport provider(SessionFactory sessions) {
        return ServiceLoader.load(ProviderSupport.class).stream().map(ServiceLoader.Provider::get)
                .filter(provider -> provider.supports(sessions)).findFirst().orElseThrow();
    }

    static CriteriaQuery<Tuple> invoice(EntityManager em, EntityJoinRenderer renderer, Options options, Kind kind) {
        var cb = em.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(Invoice.class);
        From<?, ?> anchor = options.anchor() == Anchor.ROOT ? root
                : root.join("region", options.anchor() == Anchor.LEFT_PATH ? JoinType.LEFT : JoinType.INNER);
        Expression<String> rep = anchor.get("repCode");
        Expression<String> region = options.anchor() == Anchor.ROOT ? root.get("regionCode") : anchor.get("code");
        Class<?> target = switch (options.target()) {
            case SIMPLE -> Employee.class;
            case ID_CLASS -> EmployeeIc.class;
            case EMBEDDED -> EmployeeEmb.class;
        };
        From<?, ?> employee = renderer.join(root, target, options.join(), joined -> {
            Path<?> id = options.target() == Target.EMBEDDED ? joined.get("id") : joined;
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(id.get("code"), rep));
            if (options.target() != Target.SIMPLE) {
                predicates.add(cb.equal(id.get("regionCode"), region));
            }
            if (options.filter()) {
                predicates.add(cb.equal(joined.get("active"), true));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        });
        if (kind == Kind.COUNT || kind == Kind.ROOT_ID_COUNT) {
            query.multiselect(kind == Kind.COUNT ? cb.count(root) : cb.countDistinct(root.get("id")));
        } else if (kind == Kind.PK_FIRST) {
            query.multiselect(root.get("id"));
        } else {
            query.multiselect(root.get("id"), root.get("number"), employee.get("name"));
        }
        switch (kind) {
            case KEYSET, EXPORT_NEXT -> query.where(cb.gt(root.get("id"), 20L));
            case PK_SECOND, BY_KEYS -> query.where(root.get("id").in(1L, 25L, 50L, 100L));
            case ONE -> query.where(cb.equal(root.get("id"), 50L));
            default -> { }
        }
        if (kind != Kind.COUNT && kind != Kind.ROOT_ID_COUNT) {
            query.orderBy(cb.asc(root.get("id")));
        }
        return query;
    }

    static List<String> run(SessionFactory sessions, Options options, Kind kind) {
        EntityJoinRenderer renderer = provider(sessions).entityJoins().orElseThrow();
        return run(sessions, renderer, options, kind);
    }

    static List<String> run(SessionFactory sessions, EntityJoinRenderer renderer, Options options, Kind kind) {
        return SpikeHarness.inEm(sessions, em -> {
            var query = em.createQuery(invoice(em, renderer, options, kind));
            query.setHint("jakarta.persistence.query.timeout", 60_000);
            if (kind == Kind.STREAM) {
                try (var stream = query.getResultStream()) {
                    return rows(stream.toList());
                }
            }
            if (limited(kind)) {
                query.setMaxResults(20);
            }
            return rows(query.getResultList());
        });
    }

    static boolean limited(Kind kind) {
        return switch (kind) {
            case CONTENT, PK_FIRST, KEYSET, EXPORT_FIRST, EXPORT_NEXT, FETCH_PARENT -> true;
            default -> false;
        };
    }

    static String nativeInvoice(Options options, Kind kind) {
        String columns = switch (kind) {
            case COUNT -> "count(*)";
            case ROOT_ID_COUNT -> "count(distinct i.id)";
            case PK_FIRST -> "i.id";
            default -> "i.id, i.invoice_number, e.name";
        };
        String path = options.anchor() == Anchor.ROOT ? "" : (options.anchor() == Anchor.LEFT_PATH
                ? " left join " : " join ") + "region r on r.code=i.region_code";
        String table = switch (options.target()) {
            case SIMPLE -> "employee";
            case ID_CLASS -> "employee_ic";
            case EMBEDDED -> "employee_emb";
        };
        String ref = options.anchor() == Anchor.ROOT ? "i" : "r";
        String sql = "select " + columns + " from invoice i" + path
                + (options.join() == JoinKind.LEFT ? " left join " : " join ") + table
                + " e on e.code=" + ref + ".rep_code";
        if (options.target() != Target.SIMPLE) {
            sql += " and e.region_code=" + ref + (options.anchor() == Anchor.ROOT ? ".region_code" : ".code");
        }
        if (options.filter()) {
            sql += " and e.active=true";
        }
        sql += switch (kind) {
            case KEYSET, EXPORT_NEXT -> " where i.id>20";
            case PK_SECOND, BY_KEYS -> " where i.id in (1,25,50,100)";
            case ONE -> " where i.id=50";
            default -> "";
        };
        if (kind != Kind.COUNT && kind != Kind.ROOT_ID_COUNT) {
            sql += " order by i.id";
        }
        return sql + (limited(kind) ? " limit 20" : "");
    }

    static List<String> nativeRows(SpikeHarness harness, String sql) throws SQLException {
        try (Connection connection = harness.raw().getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            try (ResultSet result = statement.executeQuery(sql)) {
                List<String> rows = new ArrayList<>();
                while (result.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                        cells.add(String.valueOf(result.getObject(column)));
                    }
                    rows.add(String.join("|", cells));
                }
                return rows;
            }
        }
    }

    static List<String> rows(List<Tuple> tuples) {
        return tuples.stream().map(tuple -> Arrays.stream(tuple.toArray()).map(String::valueOf)
                .collect(Collectors.joining("|"))).toList();
    }

    static void equal(List<String> actual, List<String> expected) {
        SpikeHarness.expect(actual.equals(expected), "rows differ: actual=" + actual.stream().limit(3).toList()
                + " (" + actual.size() + ") expected=" + expected.stream().limit(3).toList()
                + " (" + expected.size() + ")");
    }
}
