package com.rey.modelquery.tck.portability;

import com.rey.modelquery.core.ConstantRenderer;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import com.rey.modelquery.core.SequenceRenderer;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.EntityWriteSupport;
import com.rey.modelquery.jpa.spi.ForeignKeyColumn;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.LockSupport;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.ReturningSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Hibernate's {@link ProviderSupport} with no {@link ProviderSupport#sources() source support}, as a persistence
 * provider that serves the factory but ships no CTE hook does: a query over a source is then {@code MQ4014} before
 * any statement (spec api/17 R-SRC-11). Found with {@code ServiceLoader} only inside {@link #serving(Runnable)}, so
 * the offline boot still serves the factory through Hibernate's own offline properties.
 */
public class NoSourceProviderSupport implements ProviderSupport {

    private static final String SERVICE = "META-INF/services/" + ProviderSupport.class.getName();

    private final HibernateProviderSupport hibernate = new HibernateProviderSupport();

    @Override
    public boolean supports(EntityManagerFactory emf) {
        return hibernate.supports(emf);
    }

    @Override
    public Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
        return hibernate.detectVendor(emf);
    }

    @Override
    public Optional<CriteriaQuery<Long>> countQuery(CriteriaQuery<?> groupedQuery) {
        return hibernate.countQuery(groupedQuery);
    }

    @Override
    public Optional<NullPrecedenceRenderer> nullPrecedence() {
        return hibernate.nullPrecedence();
    }

    @Override
    public Optional<NullPrecedence> defaultNullPrecedence(EntityManagerFactory emf) {
        return hibernate.defaultNullPrecedence(emf);
    }

    @Override
    public Optional<InsertSupport> inserts() {
        return hibernate.inserts();
    }

    @Override
    public Optional<EntityWriteSupport> entityWrites() {
        return hibernate.entityWrites();
    }

    @Override
    public Optional<LockSupport> locks() {
        return hibernate.locks();
    }

    @Override
    public Optional<ConstantRenderer> constants(EntityManagerFactory emf) {
        return hibernate.constants(emf);
    }

    @Override
    public Optional<ReturningSupport> returning() {
        return hibernate.returning();
    }

    @Override
    public Optional<SequenceRenderer> sequences(EntityManagerFactory emf) {
        return hibernate.sequences(emf);
    }

    @Override
    public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
        return hibernate.resultStream(query, fetchSize);
    }

    @Override
    public Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity) {
        return hibernate.tablesOf(emf, entity);
    }

    @Override
    public ForeignKeyColumn foreignKeyColumn(EntityManagerFactory emf, Class<?> entity, String toOne) {
        return hibernate.foreignKeyColumn(emf, entity, toOne);
    }

    @Override
    public Optional<String> factoryName(EntityManagerFactory emf) {
        return hibernate.factoryName(emf);
    }

    @Override
    public Optional<Map<String, Object>> offlineProperties(DatabaseVendor vendor) {
        return hibernate.offlineProperties(vendor);
    }

    /** Runs {@code work} where this class is the only {@code ProviderSupport} {@code ServiceLoader} finds. */
    static void serving(Runnable work) {
        serving(NoSourceProviderSupport.class, work);
    }

    static void serving(Class<? extends ProviderSupport> provider, Runnable work) {
        URL service;
        Path file;
        try {
            file = Files.createTempFile("provider-support", ".txt");
            Files.writeString(file, provider.getName() + "\n");
            service = file.toUri().toURL();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(new ClassLoader(original) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return name.equals(SERVICE) ? Collections.enumeration(List.of(service))
                        : super.getResources(name);
            }
        });
        try {
            work.run();
        } finally {
            thread.setContextClassLoader(original);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // a temporary file left behind is harmless
            }
        }
    }
}
