package com.rey.modelquery.audit;

import com.rey.modelquery.audit.test.AuditCustomerEntity;
import com.rey.modelquery.audit.test.AuditCustomerView;
import com.rey.modelquery.audit.test.AuditLineEntity;
import com.rey.modelquery.audit.test.AuditLineView;
import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditSpot;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.AuditTestProvider;
import com.rey.modelquery.audit.test.QAuditLineView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class AuditTestBase {

    protected static SessionFactory sessions;

    @BeforeAll
    static void initDb() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:select-audit-test;DB_CLOSE_DELAY=-1")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .applySetting(AuditTestProvider.MARKER, "true");

        sessions = new Configuration()
                .addAnnotatedClass(AuditCustomerEntity.class)
                .addAnnotatedClass(AuditLineEntity.class)
                .addAnnotatedClass(AuditOrderEntity.class)
                .buildSessionFactory(registry.build());

        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            var c1 = new AuditCustomerEntity(1L, "Alice");
            var c2 = new AuditCustomerEntity(2L, "Bob");
            c1.setColleague(c2);
            em.persist(c1);
            em.persist(c2);

            var first = new AuditOrderEntity(1L, 100L, "First order", c1);
            first.getTags().addAll(List.of("rush", "gift"));
            first.getSpots().add(new AuditSpot("Rome"));
            em.persist(first);
            em.persist(new AuditOrderEntity(2L, 200L, "Second order", c2));
            em.persist(new AuditOrderEntity(3L, 300L, "Third order", null));

            em.persist(new AuditLineEntity(10L, 1L, "SKU-1"));
            em.persist(new AuditLineEntity(20L, 1L, "SKU-2"));
            em.persist(new AuditLineEntity(30L, 2L, "SKU-3"));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void closeDb() {
        if (sessions != null) {
            sessions.close();
        }
    }

    protected static ModelQueryExecutor<AuditOrderEntity> orderExecutor(EntityManager em) {
        return ModelQueryExecutor.create(em, AuditOrderEntity.class,
                ModelQueryConfig.defaults().vendor(DatabaseVendor.H2));
    }
}
