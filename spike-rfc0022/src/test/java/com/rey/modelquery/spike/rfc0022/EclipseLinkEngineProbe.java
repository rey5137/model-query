package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Employee;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Invoice;
import com.rey.modelquery.spike.rfc0022.Rfc0022Entities.Team;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;

/** Isolated API 3.2 / EclipseLink 5.0.2 process. No Hibernate factory bootstraps in this lane. */
public final class EclipseLinkEngineProbe {
    public record Row(Long id, String number, String employee, String team) {}
    private static final TableField<Invoice, Invoice> ROOT = TableField.root(Invoice.class);
    private static final OrderedColumnField<Row, Invoice, Long> ID =
            ColumnField.of(Row.class, ROOT, "id", Long.class).named("id");
    private static final ColumnField<Row, Invoice, String> NUMBER =
            ColumnField.of(Row.class, ROOT, "number", String.class).named("number");
    private static final List<String> CELLS = List.of("LEFT", "INNER", "to-one-below", "filter", "count",
            "keyset-nullsLast", "export");
    private EclipseLinkEngineProbe() {}

    public static void main(String[] args) throws Exception {
        for (String vendor : List.of("H2", "MYSQL", "POSTGRESQL")) {
            try (var h = new SpikeHarness(vendor)) {
                EntityManagerFactory[] factory = new EntityManagerFactory[1];
                try {
                    boolean ready = h.cell("A.4", "bootstrap-and-invoice-seed", () -> {
                        var provider = (PersistenceProvider) Class.forName(
                                "org.eclipse.persistence.jpa.PersistenceProvider")
                                .getConstructor().newInstance();
                        factory[0] = provider.createContainerEntityManagerFactory(new Unit(h.dataSource()), Map.of(
                                "eclipselink.weaving", "false", "eclipselink.logging.level", "WARNING",
                                "eclipselink.ddl-generation", "drop-and-create-tables",
                                "eclipselink.ddl-generation.output-mode", "database",
                                "jakarta.persistence.query.timeout", "60000"));
                        factory[0].getMetamodel();
                        try (var connection = h.raw().getConnection()) {
                            Rfc0022Dataset.seedInvoices(connection);
                        }
                        return "EclipseLink 5.0.2; invoice -> employee model; 50,000 invoices";
                    });
                    if (!ready) {
                        for (String cell : CELLS) {
                            h.notRun("A.4", cell,
                                    "stopped at EclipseLink factory bootstrap / invoice seed; see prior row");
                        }
                        continue;
                    }
                    EntityManagerFactory emf = factory[0];
                    reproduce(h, emf);
                    h.cell("A.4", "engine-vendor-and-tuple-aliases", () -> {
                        var query = ModelQuery.builder(ROOT,
                                        row -> new Row(row.get(ID), row.get(NUMBER), null, null))
                                .select(SelectSet.of(ID, NUMBER)).primaryKey(PrimaryKey.of(ID))
                                .orderBy(ID.asc()).build();
                        try (var em = emf.createEntityManager()) {
                            var actual = ModelQueryExecutor.create(em, Invoice.class, config())
                                    .list(query, Limit.of(20));
                            Q.equal(actual.stream().map(row -> row.id() + "|" + row.number()).toList(), Q.nativeRows(h,
                                    "select id, invoice_number from invoice order by id limit 20"));
                        }
                        return "production executor; automatic DatabaseMetaData vendor detection and Tuple mapping";
                    });
                    var renderer = PortableRenderer.load();
                    boolean leftReady = portable(h, emf, renderer, com.rey.modelquery.annotations.JoinKind.LEFT);
                    boolean innerReady = portable(h, emf, renderer, com.rey.modelquery.annotations.JoinKind.INNER);
                    for (String cell : CELLS) {
                        if (!(cell.equals("INNER") ? innerReady : leftReady)) {
                            h.notRun("A.4", cell,
                                    "stopped at portable Invoice -> Employee join creation; see portable-LEFT/INNER");
                            continue;
                        }
                        h.cell("A.4", cell, () -> {
                            try {
                                return engineCell(h, emf, renderer, cell);
                            } catch (Throwable failure) {
                                System.err.println("A.4 " + vendor + " " + cell + " stopped:");
                                failure.printStackTrace(System.err);
                                throw failure;
                            }
                        });
                    }
                } finally {
                    if (factory[0] != null) {
                        factory[0].close();
                    }
                    System.out.println("RFC0022 " + h.counts().replace(SpikeHarness.HIB, "EclipseLink 5.0.2"));
                }
            }
        }
    }

    static String engineCell(SpikeHarness h, EntityManagerFactory emf,
            com.rey.modelquery.core.EntityJoinRenderer renderer, String cell) throws Exception {
        try (var original = emf.createEntityManager()) {
            var em = PortableJoinAdapter.wrap(original, renderer);
            var executor = ModelQueryExecutor.create(em, Invoice.class, config());
            var query = model(cell);
            List<String> actual;
            if (cell.equals("count")) {
                long count = executor.page(query, PageSpec.of(0, 20), CountMode.COUNT)
                        .total().orElseThrow();
                actual = List.of(Long.toString(count));
            } else if (cell.equals("export")) {
                List<Row> exported = new ArrayList<>();
                executor.export(query, ExportOptions.of(1_000), page -> page, exported::add);
                actual = rows(exported, false);
            } else if (cell.equals("keyset-nullsLast")) {
                List<Row> walked = new ArrayList<>();
                KeysetSpec next = KeysetSpec.first(20);
                do {
                    var page = executor.page(query, next);
                    walked.addAll(page.content());
                    if (!page.hasNext()) {
                        break;
                    }
                    next = KeysetSpec.after(page.nextCursor().orElseThrow(), 20);
                } while (true);
                actual = rows(walked, false);
                SpikeHarness.expect(walked.stream().anyMatch(row -> row.employee() == null),
                        "nullsLast walk never reached unmatched employees");
            } else {
                actual = rows(executor.list(query, Limit.of(100)), cell.equals("to-one-below"));
            }
            Q.equal(actual, Q.nativeRows(h, nativeSql(cell)));
            return "production executor + test-only column join creation; native rows="
                    + actual.size();
        }    }

    private static ModelQueryConfig config() {
        return ModelQueryConfig.defaults().queryTimeout(Duration.ofSeconds(60))
                .keysetNullKeys(com.rey.modelquery.jpa.KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
    }

    @SuppressWarnings("unchecked")
    private static void reproduce(SpikeHarness h, EntityManagerFactory emf) throws Exception {
        var probe = (java.util.function.BiFunction<jakarta.persistence.EntityManager, String, List<String>>)
                PortableRenderer.instantiate("PlainJoinReproduction");
        for (String variant : List.of("untyped", "typed-before-on", "typed-after-on", "JPQL")) {
            h.cell("A.4-repro", variant, () -> {
                try (var em = emf.createEntityManager()) {
                    Q.equal(probe.apply(em, variant), Q.nativeRows(h, "select id from invoice order by id limit 20"));
                    return "plain Jakarta / JPQL, no engine; matches native SQL";
                } catch (Throwable failure) {
                    System.err.println("A.4-repro " + variant + ":");
                    failure.printStackTrace(System.err);
                    throw failure;
                }
            });
        }
    }

    private static boolean portable(SpikeHarness h, EntityManagerFactory emf,
            com.rey.modelquery.core.EntityJoinRenderer renderer, com.rey.modelquery.annotations.JoinKind kind) {
        return h.cell("A.4", "portable-" + kind, () -> {
            try (var em = emf.createEntityManager()) {
                var options = new Q.Options(Q.Anchor.ROOT, kind, Q.Target.SIMPLE, false);
                var query = em.createQuery(Q.invoice(em, renderer, options, Q.Kind.CONTENT));
                query.setHint("jakarta.persistence.query.timeout", 60_000).setMaxResults(20);
                Q.equal(Q.rows(query.getResultList()), Q.nativeRows(h, Q.nativeInvoice(options, Q.Kind.CONTENT)));
                return "non-self Invoice -> Employee portable join matches native SQL";
            } catch (Throwable failure) {
                failure.printStackTrace(System.err);
                throw failure;
            }
        });
    }

    static ModelQuery<Invoice, Long, Row> model(String cell) {
        TableField<Invoice, Employee> rep =
                TableField.join(ROOT, "rep", cell.equals("INNER") ? JoinType.INNER : JoinType.LEFT);
        OrderedColumnField<Row, Employee, String> name =
                ColumnField.of(Row.class, rep, "name", String.class).named("employee");
        var team = TableField.<Employee, Team>join(rep, "team", JoinType.LEFT);
        var teamName = ColumnField.of(Row.class, team, "name", String.class).named("team");
        var selected = cell.equals("to-one-below") ? SelectSet.of(ID, NUMBER, name, teamName)
                : SelectSet.of(ID, NUMBER, name);
        var builder = ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(NUMBER), row.get(name),
                        cell.equals("to-one-below") ? row.get(teamName) : null))
                .select(selected).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc())
                .customize((spec, joins, query, cb, phase) -> name.expression(joins));
        if (cell.equals("filter")) {
            var active = ColumnField.of(Row.class, rep, "active", Boolean.class);
            builder = builder.joinFilter(rep, filters -> filters.eq(active, true));
        }
        if (cell.equals("keyset-nullsLast")) {
            builder = builder.where(filters -> filters.gte(ID, 49_900L))
                    .orderBy(name.asc().nullsLast(), ID.asc()).keyset();
        }
        return builder.build();
    }

    static String nativeSql(String cell) {
        String select = cell.equals("count") ? "count(*)" : "i.id, i.invoice_number, e.name";
        if (cell.equals("to-one-below")) {
            select += ", t.name";
        }
        String sql = "select " + select + " from invoice i " + (cell.equals("INNER") ? "inner" : "left")
                + " join employee e on e.code=i.rep_code" + (cell.equals("filter") ? " and e.active=true" : "");
        if (cell.equals("to-one-below")) {
            sql += " left join team t on t.id=e.team_id";
        }
        if (cell.equals("keyset-nullsLast")) {
            return sql + " where i.id>=49900 order by case when e.name is null then 1 else 0 end, e.name, i.id";
        }
        if (cell.equals("count")) {
            return sql;
        }
        return sql + " order by i.id" + (cell.equals("export") ? "" : " limit 100");
    }

    static List<String> rows(List<Row> rows, boolean team) {
        return rows.stream().map(row -> row.id() + "|" + row.number() + "|" + row.employee()
                + (team ? "|" + row.team() : "")).toList();
    }

    private record Unit(DataSource source) implements PersistenceUnitInfo {
        @Override public String getPersistenceUnitName() { return "rfc0022-eclipselink"; }
        @Override public String getPersistenceProviderClassName() {
            return "org.eclipse.persistence.jpa.PersistenceProvider";
        }
        @Override public PersistenceUnitTransactionType getTransactionType() {
            return PersistenceUnitTransactionType.RESOURCE_LOCAL;
        }
        @Override public DataSource getJtaDataSource() { return null; }
        @Override public DataSource getNonJtaDataSource() { return source; }
        @Override public List<String> getMappingFileNames() { return List.of(); }
        @Override public List<URL> getJarFileUrls() { return List.of(); }
        @Override public URL getPersistenceUnitRootUrl() {
            return EclipseLinkEngineProbe.class.getProtectionDomain().getCodeSource().getLocation();
        }
        @Override public List<String> getManagedClassNames() {
            return Arrays.stream(Rfc0022Entities.ALL).filter(type -> type.getEnclosingClass() == Rfc0022Entities.class)
                    .map(Class::getName).toList();
        }
        @Override public boolean excludeUnlistedClasses() { return true; }
        @Override public SharedCacheMode getSharedCacheMode() { return SharedCacheMode.NONE; }
        @Override public ValidationMode getValidationMode() { return ValidationMode.NONE; }
        @Override public Properties getProperties() { return new Properties(); }
        @Override public String getPersistenceXMLSchemaVersion() { return "3.2"; }
        public String getScopeAnnotationName() { return null; }
        public List<String> getQualifierAnnotationNames() { return List.of(); }
        @Override public ClassLoader getClassLoader() { return EclipseLinkEngineProbe.class.getClassLoader(); }
        @Override public void addTransformer(ClassTransformer transformer) {}
        @Override public ClassLoader getNewTempClassLoader() { return getClassLoader(); }
    }
}
