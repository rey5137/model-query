package com.rey.modelquery.tck.wrt;

import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Hibernate's {@link ProviderSupport} with its insert support, but naming no table for any entity, as a provider
 * without {@code tablesOf} does: a chunked insert-select then cannot tell whether its source and target share a table
 * (spec api/14 R-WRT-28). Found with {@code ServiceLoader} only inside {@link #serving(Runnable)}.
 */
public final class NoTablesProviderSupport implements ProviderSupport {

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
    public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
        return hibernate.resultStream(query, fetchSize);
    }

    @Override
    public Optional<InsertSupport> inserts() {
        return hibernate.inserts();
    }

    /** Runs {@code work} where this class is the only {@code ProviderSupport} {@code ServiceLoader} finds. */
    static void serving(Runnable work) {
        serving(NoTablesProviderSupport.class, work);
    }

    /** Runs {@code work} where {@code support} is the only {@code ProviderSupport} {@code ServiceLoader} finds. */
    static void serving(Class<? extends ProviderSupport> support, Runnable work) {
        URL service;
        Path file;
        try {
            file = Files.createTempFile("provider-support", ".txt");
            Files.writeString(file, support.getName() + "\n");
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
