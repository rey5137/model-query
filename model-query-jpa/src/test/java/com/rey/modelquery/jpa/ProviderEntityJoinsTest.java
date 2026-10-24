package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProviderEntityJoinsTest {

    @Test
    void ac_src_58_entity_join_hook_defaults_to_absent() {
        ProviderSupport provider = new ProviderSupport() {
            @Override
            public boolean supports(EntityManagerFactory emf) {
                return false;
            }

            @Override
            public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
                return query.getResultStream();
            }
        };
        assertThat(provider.entityJoins()).isEmpty();
    }
}
