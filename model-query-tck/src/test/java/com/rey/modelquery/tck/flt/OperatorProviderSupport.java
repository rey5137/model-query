package com.rey.modelquery.tck.flt;

import com.rey.modelquery.core.ConstantRenderer;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import com.rey.modelquery.core.OperandBinder;
import com.rey.modelquery.core.OperatorFeature;
import com.rey.modelquery.core.SequenceRenderer;
import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.core.SubqueryRenderer;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.EntityWriteSupport;
import com.rey.modelquery.jpa.spi.ForeignKeyColumn;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.LockSupport;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.ReturningSupport;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;

/**
 * Hibernate's {@link ProviderSupport} with a test answer for {@link #operators} and a test {@link #operands} binder,
 * so the engine's operator gate and binder seam run before the provider's own answers exist (spec vendor/40
 * R-VND-23, R-VND-24). Found with {@code ServiceLoader} only inside {@link #serving}, through an in-memory service
 * declaration, so nothing is left on disk.
 */
public final class OperatorProviderSupport implements ProviderSupport {

    private static final String SERVICE = "META-INF/services/" + ProviderSupport.class.getName();
    private static final ThreadLocal<Predicate<OperatorFeature>> OPERATORS = new ThreadLocal<>();
    private static final ThreadLocal<OperandBinder> BINDER = new ThreadLocal<>();

    private final HibernateProviderSupport hibernate = new HibernateProviderSupport();

    @Override
    public boolean operators(EntityManagerFactory emf, OperatorFeature feature) {
        return OPERATORS.get().test(feature);
    }

    @Override
    public Optional<OperandBinder> operands(EntityManagerFactory emf) {
        return Optional.ofNullable(BINDER.get());
    }

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
    public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
        return hibernate.resultStream(query, fetchSize);
    }

    @Override
    public Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity) {
        return hibernate.tablesOf(emf, entity);
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
    public ForeignKeyColumn foreignKeyColumn(EntityManagerFactory emf, Class<?> entity, String toOne) {
        return hibernate.foreignKeyColumn(emf, entity, toOne);
    }

    @Override
    public Optional<Map<String, Object>> offlineProperties(DatabaseVendor vendor) {
        return hibernate.offlineProperties(vendor);
    }

    @Override
    public Optional<String> factoryName(EntityManagerFactory emf) {
        return hibernate.factoryName(emf);
    }

    @Override
    public Optional<SourceRenderer> sources() {
        return hibernate.sources();
    }

    @Override
    public Optional<SubqueryRenderer> subqueries() {
        return hibernate.subqueries();
    }

    @Override
    public Optional<TreatRenderer> treats(EntityManagerFactory emf) {
        return hibernate.treats(emf);
    }

    /**
     * Runs {@code work} where this class is the only {@code ProviderSupport} {@code ServiceLoader} finds, answering
     * {@code operators} for each feature and supplying {@code binder}, or none when it is {@code null}.
     */
    public static void serving(Predicate<OperatorFeature> operators, OperandBinder binder, Runnable work) {
        byte[] declaration = (OperatorProviderSupport.class.getName() + "\n").getBytes(StandardCharsets.UTF_8);
        URL url;
        try {
            url = new URL(null, "memory:operator-provider-support", new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL target) {
                    return new URLConnection(target) {
                        @Override
                        public void connect() {}

                        @Override
                        public InputStream getInputStream() {
                            return new ByteArrayInputStream(declaration);
                        }
                    };
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        OPERATORS.set(operators);
        BINDER.set(binder);
        thread.setContextClassLoader(new ClassLoader(original) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return SERVICE.equals(name) ? Collections.enumeration(List.of(url)) : super.getResources(name);
            }
        });
        try {
            work.run();
        } finally {
            thread.setContextClassLoader(original);
            OPERATORS.remove();
            BINDER.remove();
        }
    }

    /** The factory's built-in profile, answering {@code operators} as {@code vendor} says (a custom profile). */
    public static VendorProfile answering(SessionFactory sf, Predicate<OperatorFeature> vendor) {
        VendorProfile builtIn = VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile();
        return new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return builtIn.vendor();
            }

            @Override
            public int maxInListSize() {
                return builtIn.maxInListSize();
            }

            @Override
            public int maxBindParameters() {
                return builtIn.maxBindParameters();
            }

            @Override
            public void applyTimeout(Query query, Duration timeout) {
                builtIn.applyTimeout(query, timeout);
            }

            @Override
            public NullOrdering defaultAscendingNullOrdering() {
                return builtIn.defaultAscendingNullOrdering();
            }

            @Override
            public boolean operators(OperatorFeature feature) {
                return vendor.test(feature);
            }
        };
    }
}
