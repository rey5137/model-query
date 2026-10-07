package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/**
 * A query model and an update model over one entity sharing their accessors through an interface (the "Shared
 * accessors" section of the models guide). Runs on H2 in a transaction that is rolled back.
 */
class SharedAccessorModelTest {

    private static final TckDatabase DB = TckDatabases.get(TckTarget.h2());

    @Test
    void a_query_model_and_an_update_model_share_accessors_through_an_interface() {
        inRolledBackTransaction(em -> {
            ModelQueryExecutor<CustomerEntity> customers =
                    ModelQueryExecutor.create(em, CustomerEntity.class, ModelQueryConfig.defaults());
            long id = ((Number) em.createNativeQuery("select min(id) from customers").getSingleResult()).longValue();

            CustomerCard card = card(customers, id);
            var patch = new CustomerContactPatch(id, card.name(), "NO");
            long written = customers.update(QCustomerContactPatch.update(
                    QCustomerContactPatch.changes().country(patch.country())).whereKey(id).build());
            em.clear();

            List<CustomerContact> both = List.of(patch, card(customers, id));
            assertThat(written).isEqualTo(1);
            assertThat(both).extracting(CustomerContact::label)
                    .containsExactly(card.name() + " (NO)", card.name() + " (NO)");
        });
    }

    private static CustomerCard card(ModelQueryExecutor<CustomerEntity> customers, long id) {
        var byId = QCustomerCard.query()
                .select(QCustomerCard.ALL)
                .where(f -> f.eq(QCustomerCard.ID, id))
                .build();
        return customers.list(byId, Limit.unlimited()).get(0);
    }

    private static void inRolledBackTransaction(Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(DB)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.accept(em);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
