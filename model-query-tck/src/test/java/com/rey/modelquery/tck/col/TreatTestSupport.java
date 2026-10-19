package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.Root;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** Shared mapped inheritance fixtures and a provider stub for treat contracts. */
public final class TreatTestSupport {

    private TreatTestSupport() {}

    public static final TableField<Payment, Payment> ROOT = TableField.root(Payment.class);
    public static final TableField<Payment, Card> CARD = ROOT.treat(Card.class);
    public static final ColumnField<String, Payment, Long> ID = ColumnField.of(String.class, ROOT, "id", Long.class);
    public static final ColumnField<String, Card, String> NUMBER =
            ColumnField.of(String.class, CARD, "number", String.class);
    public static final ColumnField<String, Payment, String> STATUS =
            ColumnField.of(String.class, ROOT, "status", String.class);
    public static final TableField<Holder, Holder> HOLDERS = TableField.root(Holder.class);
    public static final TableField<Holder, Payment> PAYMENT = TableField.join(HOLDERS, "payment", JoinType.LEFT);
    public static final TableField<Holder, Card> CARD_JOIN = TableField.join(HOLDERS, "card", JoinType.LEFT);
    public static final TableField<Holder, Bank> BANK_JOIN = TableField.join(HOLDERS, "bank", JoinType.LEFT);

    public static SessionFactory factory(DataSource ds) {
        Configuration cfg = new Configuration().addAnnotatedClass(Payment.class).addAnnotatedClass(Card.class)
                .addAnnotatedClass(Bank.class).addAnnotatedClass(Premium.class).addAnnotatedClass(Holder.class);
        cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
        cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
        return cfg.buildSessionFactory();
    }

    /** Owns and removes the mapped tables, outside the SQL capture used for refusal assertions. */
    public static SessionFactory schema(TckDatabase db) {
        return new Configuration().addAnnotatedClass(Payment.class).addAnnotatedClass(Card.class)
                .addAnnotatedClass(Bank.class).addAnnotatedClass(Premium.class).addAnnotatedClass(Holder.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop").buildSessionFactory();
    }

    public static final class CountingRenderer implements TreatRenderer {
        public int calls;

        @Override
        @SuppressWarnings("unchecked")
        public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
            calls++;
            if (from instanceof Root<?>) {
                return cb.treat((Root<T>) from, subtype);
            }
            if (from instanceof ListJoin<?, ?>) {
                return cb.treat((ListJoin<?, T>) from, subtype);
            }
            return cb.treat((Join<?, T>) from, subtype);
        }

        @Override
        public Optional<String> refusal(TreatSite site) {
            return Optional.empty();
        }

        @Override
        public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement,
                    StatementShape shape) {
            return Optional.empty();
        }
    }

    public static final class ServingProvider implements ProviderSupport {
        private static final ThreadLocal<EntityManagerFactory> FACTORY = new ThreadLocal<>();
        private static final ThreadLocal<TreatRenderer> RENDERER = new ThreadLocal<>();

        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf == FACTORY.get();
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            throw new AssertionError("no statement is executed");
        }

        @Override
        public Optional<TreatRenderer> treats(EntityManagerFactory emf) {
            assertThat(emf).isSameAs(FACTORY.get());
            return Optional.ofNullable(RENDERER.get());
        }

        public static void serving(EntityManagerFactory factory, TreatRenderer renderer, Runnable work) {
            Thread thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            String service = "META-INF/services/" + ProviderSupport.class.getName();
            byte[] declaration = (ServingProvider.class.getName() + "\n").getBytes(StandardCharsets.UTF_8);
            URL url;
            try {
                url = new URL(null, "memory:provider-support", new URLStreamHandler() {
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
            FACTORY.set(factory);
            RENDERER.set(renderer);
            thread.setContextClassLoader(new ClassLoader(original) {
                @Override
                public Enumeration<URL> getResources(String name) throws IOException {
                    return service.equals(name) ? Collections.enumeration(List.of(url)) : super.getResources(name);
                }
            });
            try {
                work.run();
            } finally {
                thread.setContextClassLoader(original);
                FACTORY.remove();
                RENDERER.remove();
            }
        }
    }

    @Entity(name = "TreatPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class Payment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "TreatCard")
    public static class Card extends Payment {
        public String number;
        @ManyToOne public Holder issuer;
    }

    @Entity(name = "TreatPremium")
    public static class Premium extends Card {}

    @Entity(name = "TreatBank")
    public static class Bank extends Payment {}

    public static class Unmapped extends Payment {}

    @Entity(name = "TreatHolder")
    public static class Holder {
        @Id public Long id;
        public String label;
        @ManyToOne public Payment payment;
        @ManyToOne public Card card;
        @ManyToOne public Bank bank;
        @OneToMany public List<Payment> payments;
    }
}
