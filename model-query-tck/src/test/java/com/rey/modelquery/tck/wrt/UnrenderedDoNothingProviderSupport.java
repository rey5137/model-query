package com.rey.modelquery.tck.wrt;

import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.spi.ConflictClause;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.InsertTarget;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Hibernate's {@link ProviderSupport}, whose insert support reports that it renders no {@code doNothing}, as Hibernate
 * 6.6 reports for a {@code MERGE} vendor: a {@code doNothing} conflict clause is then refused on any vendor (spec
 * api/14 R-WRT-34). Found with {@code ServiceLoader} only inside {@link NoTablesProviderSupport#serving}.
 */
public final class UnrenderedDoNothingProviderSupport implements ProviderSupport {

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
    public Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity) {
        return hibernate.tablesOf(emf, entity);
    }

    @Override
    public Optional<InsertSupport> inserts() {
        InsertSupport inserts = hibernate.inserts().orElseThrow();
        return Optional.of(new InsertSupport() {
            @Override
            public InsertTarget target(EntityManagerFactory emf, Class<?> entity) {
                return inserts.target(emf, entity);
            }

            @Override
            public List<Object> generateKeys(EntityManager em, Class<?> entity, int count) {
                return inserts.generateKeys(em, entity, count);
            }

            @Override
            public boolean doNothingRendered(EntityManagerFactory emf) {
                return false;
            }

            @Override
            public int providerBindsPerRow(EntityManagerFactory emf, Class<?> entity) {
                return inserts.providerBindsPerRow(emf, entity);
            }

            @Override
            public List<Set<String>> uniqueKeys(EntityManagerFactory emf, Class<?> entity) {
                return inserts.uniqueKeys(emf, entity);
            }

            @Override
            public <E> Query insertSelect(EntityManager em, Class<E> entity, List<String> attributes,
                    CriteriaQuery<Tuple> source, Map<String, Object> constants) {
                return inserts.insertSelect(em, entity, attributes, source, constants);
            }

            @Override
            public <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
                    List<List<Object>> rows, Optional<ConflictClause<E>> conflict) {
                return inserts.insertValues(em, entity, attributes, rows, conflict);
            }
        });
    }
}
