import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Compiled once against API 3.2; exactly these class files run in every isolated provider/API lane. */
public class LinkProbe {
    @Entity(name = "ProbeEntity")
    public static class ProbeEntity {
        @Id public Long id;
    }

    public interface Renderer {
        Join<?, ?> join(From<?, ?> root, jakarta.persistence.criteria.CriteriaBuilder cb, boolean equality);
    }

    public static void main(String[] args) throws Exception {
        String provider = args[0];
        String lane = args[1];
        Path output = Path.of(args[2]);
        List<String> rows = new ArrayList<>();
        rows.add("vendor\tprobe\tcase\tstatus\tvalue\tsql");
        CountingSource source = new CountingSource();
        var unit = new Unit(provider, source);
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("hibernate.dialect", "org.hibernate.dialect.H2Dialect"),
                Map.entry("hibernate.boot.allow_jdbc_metadata_access", false),
                Map.entry("hibernate.hbm2ddl.auto", "none"),
                Map.entry("eclipselink.target-database", "org.eclipse.persistence.platform.database.H2Platform"),
                Map.entry("eclipselink.weaving", "false"),
                Map.entry("eclipselink.ddl-generation", "none"),
                Map.entry("eclipselink.logging.level", "WARNING"));
        EntityManagerFactory factory;
        try {
            var implementation = (PersistenceProvider) Class.forName(provider).getConstructor().newInstance();
            factory = implementation.createContainerEntityManagerFactory(unit, properties);
            if (factory == null) {
                throw new IllegalStateException("provider declined persistence unit");
            }
            // Force lazy deployment before the no-connection boundary; bootstrap is measured separately.
            factory.getMetamodel().getEntities();
            factory.getCriteriaBuilder();
        } catch (Throwable failure) {
            record(rows, lane, "A.2", "bootstrap", "NOT_RUN", describe(failure));
            Files.write(output, rows);
            return;
        }
        System.out.println("bootstrap_connections=" + source.opens);
        source.opens = 0;
        source.allowConnections = false;
        try (factory) {
            Renderer renderer;
            try {
                Class<?> type = Class.forName("PortableRenderer", false, LinkProbe.class.getClassLoader());
                record(rows, lane, "A.2", "class-load", "PASS", type.getName());
                renderer = (Renderer) type.getConstructor().newInstance();
                record(rows, lane, "A.2", "initialize-verify", "PASS", "-Xverify:all; constructor returned");
            } catch (Throwable failure) {
                record(rows, lane, "A.2", "initialize-verify", "FAIL", describe(failure));
                Files.write(output, rows);
                return;
            }
            for (boolean equality : new boolean[] {false, true}) {
                long started = System.nanoTime();
                String name = equality ? "id-self-equality" : "conjunction";
                try {
                    String result = attempt(factory, renderer, equality);
                    double cold = (System.nanoTime() - started) / 1_000.0;
                    double[] times = new double[7];
                    for (int index = 0; index < times.length; index++) {
                        started = System.nanoTime();
                        attempt(factory, renderer, equality);
                        times[index] = (System.nanoTime() - started) / 1_000.0;
                    }
                    Arrays.sort(times);
                    boolean expectedSuccess = !lane.startsWith("hibernate-6.6") && !lane.startsWith("eclipselink-4");
                    record(rows, lane, "A.2", name, expectedSuccess && source.opens == 0 ? "PASS" : "FAIL",
                            "first_call=PASS; " + result + "; connections=" + source.opens
                            + "; cold_us=" + cold + "; median_7_us=" + times[3] + " (info only)");
                } catch (Throwable failure) {
                    Class<? extends LinkageError> expected = lane.equals("hibernate-6.6-api3.1")
                            ? NoSuchMethodError.class : AbstractMethodError.class;
                    boolean refusedAsExpected = (lane.startsWith("hibernate-6.6") || lane.startsWith("eclipselink-4"))
                            && failure.getClass() == expected && source.opens == 0;
                    record(rows, lane, "A.2", name, refusedAsExpected ? "PASS" : "FAIL",
                            "first_call=" + describe(failure) + "; connections=" + source.opens
                            + "; cold_us=" + (System.nanoTime() - started) / 1_000.0 + " (info only)"
                            + "; expected=" + ((lane.startsWith("hibernate-6.6") || lane.startsWith("eclipselink-4"))
                                    ? expected.getName() : "jakarta.Join with non-null getOn"));
                }
            }
            if (lane.startsWith("hibernate-6.6") || lane.startsWith("eclipselink-4")) {
                record(rows, lane, "A.1", "signatures-and-implementation", "NOT_RUN",
                        "A.1 implementation contract applies to Hibernate 7.4 and EclipseLink 5 only");
                Files.write(output, rows);
                return;
            }
            try {
                var method = From.class.getMethod("join", Class.class, JoinType.class);
                String result = attempt(factory, renderer, false);
                record(rows, lane, "A.1", "signatures-and-implementation", source.opens == 0 ? "PASS" : "FAIL",
                        method.toGenericString() + "; " + result + "; connections=" + source.opens);
            } catch (Throwable failure) {
                record(rows, lane, "A.1", "signatures-and-implementation", "FAIL", describe(failure));
            }
        }
        Files.write(output, rows);
    }

    private static String attempt(EntityManagerFactory factory, Renderer renderer, boolean equality) {
        var cb = factory.getCriteriaBuilder();
        var root = cb.createTupleQuery().from(ProbeEntity.class);
        Join<?, ?> joined = renderer.join(root, cb, equality);
        if (joined.getOn() == null) {
            throw new IllegalStateException("getOn() is null after one on");
        }
        return "jakarta.Join=true; getOn=true; root=" + root.getClass().getName()
                + "; joined=" + joined.getClass().getName();
    }

    private static void record(List<String> rows, String lane, String probe, String cell, String status, String value) {
        String clean = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        rows.add(String.join("\t", "OFFLINE", probe, lane + "/" + cell, status, clean, ""));
        System.out.println(probe + " " + lane + "/" + cell + " " + status + " " + clean);
    }

    private static String describe(Throwable failure) {
        StringBuilder value = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!value.isEmpty()) {
                value.append(" <- ");
            }
            value.append(cause.getClass().getName()).append(": ").append(cause.getMessage());
        }
        return value.toString();
    }

    private static final class CountingSource implements DataSource {
        int opens;
        boolean allowConnections = true;
        @Override public Connection getConnection() throws SQLException {
            opens++;
            if (!allowConnections) {
                throw new SQLException("connection attempted inside discovery probe");
            }
            return DriverManager.getConnection("jdbc:h2:mem:rfc0022_link", "sa", "");
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return getConnection();
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("not wrapped"); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }

    private record Unit(String provider, DataSource source) implements PersistenceUnitInfo {
        @Override public String getPersistenceUnitName() { return "rfc0022-link"; }
        @Override public String getPersistenceProviderClassName() { return provider; }
        @Override public PersistenceUnitTransactionType getTransactionType() {
            return PersistenceUnitTransactionType.RESOURCE_LOCAL;
        }
        @Override public DataSource getJtaDataSource() { return null; }
        @Override public DataSource getNonJtaDataSource() { return source; }
        @Override public List<String> getMappingFileNames() { return List.of(); }
        @Override public List<URL> getJarFileUrls() { return List.of(); }
        @Override public URL getPersistenceUnitRootUrl() {
            return LinkProbe.class.getProtectionDomain().getCodeSource().getLocation();
        }
        @Override public List<String> getManagedClassNames() { return List.of(ProbeEntity.class.getName()); }
        @Override public boolean excludeUnlistedClasses() { return true; }
        @Override public SharedCacheMode getSharedCacheMode() { return SharedCacheMode.NONE; }
        @Override public ValidationMode getValidationMode() { return ValidationMode.NONE; }
        @Override public Properties getProperties() { return new Properties(); }
        @Override public String getPersistenceXMLSchemaVersion() { return "3.1"; }
        public String getScopeAnnotationName() { return null; }
        public List<String> getQualifierAnnotationNames() { return List.of(); }
        @Override public ClassLoader getClassLoader() { return LinkProbe.class.getClassLoader(); }
        @Override public void addTransformer(ClassTransformer transformer) {}
        @Override public ClassLoader getNewTempClassLoader() { return getClassLoader(); }
    }
}

/** The proposed A.2 calls: plain interface instructions compiled against 3.2, with no reflection. */
class PortableRenderer implements LinkProbe.Renderer {
    public PortableRenderer() {}
    @Override public Join<?, ?> join(From<?, ?> root, jakarta.persistence.criteria.CriteriaBuilder cb,
            boolean equality) {
        Join<?, ?> joined = root.join(LinkProbe.ProbeEntity.class, JoinType.LEFT);
        Predicate predicate = equality ? cb.equal(joined.get("id"), root.get("id")) : cb.conjunction();
        joined.on(predicate);
        return joined;
    }
}
