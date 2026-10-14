package com.rey.modelquery.tck.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.tck.col.CustomerEntity;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;
import org.springframework.util.ClassUtils;

/**
 * AC-INS-12: the two snippets of docs/site/docs/portability.md ("Plain JPA" and "Spring repository") run as written,
 * over a TCK package in place of {@code com.example.orders}. Keep each snippet's calls identical to the page's.
 */
class PortabilityDocsSnippetTest {

    record Customer(Long id, String name) {}

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Customer, CustomerEntity, Long> ID =
            ColumnField.of(Customer.class, CUSTOMERS, "id", Long.class);
    private static final ColumnField<Customer, CustomerEntity, String> NAME =
            ColumnField.of(Customer.class, CUSTOMERS, "name", String.class);
    private static final ColumnField<Customer, CustomerEntity, String> COUNTRY =
            ColumnField.of(Customer.class, CUSTOMERS, "country", String.class);

    /** The page's {@code OrderQueries}. */
    static final class CustomerQueries {
        static final ModelQuery<CustomerEntity, Long, Customer> NEXT_PENDING = ModelQuery
                .builder(CUSTOMERS, row -> new Customer(row.get(ID), row.get(NAME)))
                .select(SelectSet.of(ID, NAME)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc()).build();
        static final ModelUpdate<CustomerEntity, ?> RENAME = ModelUpdate.builder(CUSTOMERS)
                .primaryKey(PrimaryKey.of(ID)).set(NAME, "renamed").where(f -> f.eq(COUNTRY, "NL")).build();
        static final ModelUpdate<CustomerEntity, ?> RENAME_NOBODY = ModelUpdate.builder(CUSTOMERS)
                .primaryKey(PrimaryKey.of(ID)).set(NAME, "renamed").whereKeys(List.of()).build();
        /** A returning query takes no orderBy (MQ1809). */
        static final ModelQuery<CustomerEntity, Long, Customer> RENAMED = ModelQuery
                .builder(CUSTOMERS, row -> new Customer(row.get(ID), row.get(NAME)))
                .select(SelectSet.of(ID, NAME)).primaryKey(PrimaryKey.of(ID)).build();
    }

    private static final String SCANNED_PACKAGE = "com.rey.modelquery.tck.col";

    @Test
    void ac_ins_12_docs_plain_jpa_snippet_fails_on_the_one_target_that_refuses_a_returning_write(@TempDir Path dir)
            throws IOException {
        Path xml = dir.resolve("persistence.xml");
        StringBuilder unit = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0">
                <persistence-unit name="shop" transaction-type="RESOURCE_LOCAL">
                """);
        unit.append("<provider>").append(HibernatePersistenceProvider.class.getName()).append("</provider>\n");
        scanned().forEach(c -> unit.append("<class>").append(c.getName()).append("</class>\n"));
        unit.append("""
                <exclude-unlisted-classes>true</exclude-unlisted-classes>
                </persistence-unit>
                </persistence>
                """);
        Files.writeString(xml, unit);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new PersistenceXmlLoader(previous, xml.toUri().toURL()));
        try {
            assertThatThrownBy(() ->
            // ---- the page's "Plain JPA" tab
            PortabilityCheck.of("shop", ModelQueryConfig.defaults())
                    .targets(Target.of(DatabaseVendor.H2), Target.of(DatabaseVendor.POSTGRESQL, "16.4"),
                            Target.of(DatabaseVendor.MYSQL, "8.4.0"))
                    .sweep(CustomerQueries.class)
                    .skip(CustomerQueries.class, "RENAME_NOBODY", "whereKeys of no key runs no statement")
                    .call("claim next", CustomerEntity.class,
                            x -> x.first(CustomerQueries.NEXT_PENDING.withLock(RowLock.exclusive())))
                    .call("rename returning", CustomerEntity.class,
                            x -> x.updateReturning(CustomerQueries.RENAME, CustomerQueries.RENAMED))
                    .assertPortable())
                    .isInstanceOf(AssertionError.class)
                    .satisfies(e -> {
                        List<String> lines = List.of(e.getMessage().split("\n"));
                        assertThat(lines.get(0)).startsWith("Not portable: 1 of ");
                        assertThat(lines).filteredOn(l -> l.contains(": REFUSED")).singleElement().asString()
                                .startsWith("rename returning on MYSQL 8.4.0: REFUSED MQ4011: ");
                        assertThat(lines).filteredOn(l -> l.startsWith("claim next on "))
                                .hasSize(3).allMatch(l -> l.contains(": REACHED select"));
                        assertThat(lines).contains("CustomerQueries.RENAME_NOBODY skipped: whereKeys of no key runs "
                                + "no statement");
                    });
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void ac_ins_12_docs_spring_snippet_scans_the_entities_and_passes_on_the_declared_targets() {
        // ---- the page's "Spring repository" tab
        List<Class<?>> entities = new PersistenceManagedTypesScanner(new DefaultResourceLoader())
                .scan(SCANNED_PACKAGE).getManagedClassNames().stream()
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, null))
                .toList();
        assertThat(entities).contains(CustomerEntity.class).allSatisfy(c -> assertThat(c.getPackageName())
                .isEqualTo(SCANNED_PACKAGE));
        assertThatCode(() -> PortabilityCheck.of(entities, ModelQueryConfig.defaults().targetVendors("postgresql", "h2"))
                .properties(Map.of(
                        "hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName(),
                        "hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName()))
                .mappingFiles("META-INF/shop-orm.xml")
                .sweep(CustomerQueries.class)
                .skip(CustomerQueries.class, "RENAME_NOBODY", "whereKeys of no key runs no statement")
                .call("claim next", CustomerEntity.class,
                        x -> x.first(CustomerQueries.NEXT_PENDING.withLock(RowLock.exclusive())))
                .call("rename returning", CustomerEntity.class,
                        x -> x.updateReturning(CustomerQueries.RENAME, CustomerQueries.RENAMED))
                .assertPortable()).doesNotThrowAnyException();
    }

    private static List<Class<?>> scanned() {
        return new PersistenceManagedTypesScanner(new DefaultResourceLoader())
                .scan(SCANNED_PACKAGE).getManagedClassNames().stream()
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, null))
                .toList();
    }

    /** Serves one {@code persistence.xml} for a named unit. */
    private static final class PersistenceXmlLoader extends ClassLoader {
        private static final String PERSISTENCE_XML = "META-INF/persistence.xml";
        private final URL xml;

        PersistenceXmlLoader(ClassLoader parent, URL xml) {
            super(parent);
            this.xml = xml;
        }

        @Override
        public URL getResource(String name) {
            return PERSISTENCE_XML.equals(name) ? xml : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return PERSISTENCE_XML.equals(name) ? Collections.enumeration(List.of(xml)) : super.getResources(name);
        }
    }
}
