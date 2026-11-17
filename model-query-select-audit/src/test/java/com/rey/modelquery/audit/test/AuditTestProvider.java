package com.rey.modelquery.audit.test;

import com.rey.modelquery.jpa.spi.ConflictClause;
import com.rey.modelquery.jpa.spi.IdGeneration;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.InsertTarget;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.ReturningShape;
import com.rey.modelquery.jpa.spi.ReturningSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** A test {@link ProviderSupport} providing mock returning rows for write statements. */
public final class AuditTestProvider implements ProviderSupport {

    public static final String MARKER = "audit.test.provider";

    @Override
    public boolean supports(EntityManagerFactory emf) {
        return emf.getProperties().containsKey(MARKER);
    }

    @Override
    public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
        return query.getResultStream();
    }

    @Override
    public Optional<InsertSupport> inserts() {
        return Optional.of(new InsertSupport() {
            @Override
            public InsertTarget target(EntityManagerFactory emf, Class<?> entity) {
                return new InsertTarget(new IdGeneration.Assigned(), List.of());
            }

            @Override
            public List<Object> generateKeys(EntityManager em, Class<?> entity, int count) {
                return List.of();
            }

            @Override
            public boolean doNothingRendered(EntityManagerFactory emf) {
                return true;
            }

            @Override
            public int providerBindsPerRow(EntityManagerFactory emf, Class<?> entity) {
                return 0;
            }

            @Override
            public List<Set<String>> uniqueKeys(EntityManagerFactory emf, Class<?> entity) {
                return List.of();
            }

            @Override
            public <E> Query insertSelect(EntityManager em, Class<E> entity, List<String> attributes,
                    CriteriaQuery<Tuple> source, Map<String, Object> constants) {
                return em.createNativeQuery("SELECT 1");
            }

            @Override
            public <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
                    List<List<Object>> rows, Optional<ConflictClause<E>> conflict) {
                return em.createNativeQuery("SELECT 1");
            }

            @Override
            public <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
                    List<List<Object>> rows, Map<String, Function<CriteriaBuilder, Expression<?>>> computed,
                    Optional<ConflictClause<E>> conflict) {
                return em.createNativeQuery("SELECT 1");
            }
        });
    }

    @Override
    public Optional<ReturningSupport> returning() {
        return Optional.of(new ReturningSupport() {
            @Override
            public boolean rendered(EntityManagerFactory emf, Class<?> entity, ReturningShape shape) {
                return true;
            }

            @Override
            public void check(EntityManagerFactory emf, Class<?> entity, List<String> attributes) {}

            @Override
            public List<Object[]> rows(Query statement, Class<?> entity, List<String> attributes,
                    ReturningShape shape) {
                List<Object[]> rows = new ArrayList<>();
                Object[] row = new Object[attributes.size()];
                for (int i = 0; i < attributes.size(); i++) {
                    String attr = attributes.get(i);
                    if ("id".equals(attr)) {
                        row[i] = 10L;
                    } else if ("total".equals(attr)) {
                        row[i] = 100L;
                    } else if ("notes".equals(attr)) {
                        row[i] = "returning";
                    }
                }
                rows.add(row);
                return rows;
            }
        });
    }
}
