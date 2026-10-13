package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.ConflictClause;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.InsertTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@code InsertSupport}'s six-argument {@code insertValues} default: an implementation written before it delegates
 * when there is no computed value and refuses one with {@code MQ1613} rather than dropping it, and the engine, where
 * {@code computedValuesRendered} is false, refuses it first, before the flush (vendor/40 R-VND-14, api/14 R-WRT-53,
 * D-132).
 */
class ComputedInsertValuesTest {

    static final class Counter {}

    /** An {@code InsertSupport} with only the five-argument {@code insertValues}, recording its calls. */
    static class Legacy implements InsertSupport {

        final List<List<String>> calls = new ArrayList<>();

        @Override
        public InsertTarget target(EntityManagerFactory emf, Class<?> entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Object> generateKeys(EntityManager em, Class<?> entity, int count) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
        }

        @Override
        public <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
                List<List<Object>> rows, Optional<ConflictClause<E>> conflict) {
            calls.add(attributes);
            return null;
        }
    }

    @Test
    void ac_wrt_46_the_default_delegates_without_a_computed_value_and_refuses_one_with_mq1613() {
        var legacy = new Legacy();
        legacy.insertValues(null, Counter.class, List.of("id"), List.of(List.of(1L)), Map.of(), Optional.empty());
        assertThat(legacy.calls).containsExactly(List.of("id"));

        assertThatThrownBy(() -> legacy.insertValues(null, Counter.class, List.of("id"), List.of(List.of(1L)),
                Map.of("nextValue", cb -> cb.literal(1L)), Optional.empty()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1613))
                .hasMessage("MQ1613: Counter: setExpression(...) on [nextValue], which this provider's InsertSupport ("
                        + Legacy.class.getName() + ") cannot render in an insert's VALUES");
        assertThat(legacy.calls).hasSize(1);
    }

    /** A provider's {@code InsertSupport} that renders computed values: it overrides the six-argument form. */
    static final class Rendering extends Legacy {

        @Override
        public <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
                List<List<Object>> rows, Map<String, Function<CriteriaBuilder, Expression<?>>> computed,
                Optional<ConflictClause<E>> conflict) {
            calls.add(attributes);
            return null;
        }

        @Override
        public boolean computedValuesRendered(EntityManagerFactory emf) {
            return true;
        }
    }

    /** A wrapper around a provider's {@code InsertSupport}, delegating every method it overrides to it. */
    static final class Delegating extends Legacy {

        private final InsertSupport delegate;
        private final boolean delegatesRendered;

        Delegating(InsertSupport delegate, boolean delegatesRendered) {
            this.delegate = delegate;
            this.delegatesRendered = delegatesRendered;
        }

        @Override
        public <E> Query insertValues(EntityManager em, Class<E> entity, List<String> attributes,
                List<List<Object>> rows, Map<String, Function<CriteriaBuilder, Expression<?>>> computed,
                Optional<ConflictClause<E>> conflict) {
            return delegate.insertValues(em, entity, attributes, rows, computed, conflict);
        }

        @Override
        public boolean computedValuesRendered(EntityManagerFactory emf) {
            return delegatesRendered ? delegate.computedValuesRendered(emf) : super.computedValuesRendered(emf);
        }
    }

    @Test
    void ac_wrt_46_the_engine_refuses_a_computed_value_the_support_does_not_render_before_any_statement() {
        assertThatThrownBy(() -> InsertChecks.checkComputedRendered("insert(Counter)", List.of("nextValue"),
                new Legacy(), null))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1613))
                .hasMessage("MQ1613: insert(Counter): setExpression(...) on [nextValue], which this provider's "
                        + "InsertSupport (" + Legacy.class.getName() + ") cannot render in an insert's VALUES: "
                        + "InsertSupport#computedValuesRendered is false for it");
        assertThatCode(() -> InsertChecks.checkComputedRendered("insert(Counter)", List.of(), new Legacy(), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> InsertChecks.checkComputedRendered("insert(Counter)", List.of("nextValue"),
                new Rendering(), null)).doesNotThrowAnyException();
        assertThat(new Legacy().computedValuesRendered(null)).as("the default").isFalse();
    }

    @Test
    void ac_wrt_46_a_delegating_support_renders_computed_values_when_it_delegates_computed_values_rendered() {
        assertThatCode(() -> InsertChecks.checkComputedRendered("insert(Counter)", List.of("nextValue"),
                new Delegating(new Rendering(), true), null)).doesNotThrowAnyException();
        // Overriding the six-argument insertValues alone is not enough: the capability is what the engine reads.
        assertThatThrownBy(() -> InsertChecks.checkComputedRendered("insert(Counter)", List.of("nextValue"),
                new Delegating(new Rendering(), false), null))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1613))
                .hasMessageContaining(Delegating.class.getName());
        // Delegating to a support that does not render them is refused as that support is.
        assertThatThrownBy(() -> InsertChecks.checkComputedRendered("insert(Counter)", List.of("nextValue"),
                new Delegating(new Legacy(), true), null))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1613));
    }
}
