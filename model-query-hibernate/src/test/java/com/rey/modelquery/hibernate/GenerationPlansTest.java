package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.jpa.spi.IdGeneration;
import java.util.List;
import org.hibernate.id.CompositeNestedGeneratedValueGenerator;
import org.hibernate.id.enhanced.SequenceStyleGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateInsertSupport} reads a composite id's generation plans from a private Hibernate field, and fails
 * closed when it cannot: an unreadable field counts as a generated part, so the root is refused with {@code MQ1805}
 * rather than written (R-VND-14, D-117).
 */
class GenerationPlansTest {

    /** A generator shaped as Hibernate's, its plans held as given. */
    static final class Plans {
        @SuppressWarnings("unused")
        private final Object generationPlans;

        Plans(Object generationPlans) {
            this.generationPlans = generationPlans;
        }
    }

    /** A generator with no such field. */
    static final class NoPlans {
    }

    @Test
    void ac_vnd_11_an_empty_plan_list_is_no_generated_part_and_a_held_plan_is_one() {
        assertThat(HibernateInsertSupport.holdsPlans(Plans.class, new Plans(List.of()))).isFalse();
        assertThat(HibernateInsertSupport.holdsPlans(Plans.class, new Plans(List.of("plan")))).isTrue();
    }

    @Test
    void ac_vnd_11_a_missing_null_or_mistyped_plan_field_counts_as_a_generated_part() {
        assertThat(HibernateInsertSupport.holdsPlans(NoPlans.class, new NoPlans())).isTrue();
        assertThat(HibernateInsertSupport.holdsPlans(Plans.class, new Plans(null))).isTrue();
        assertThat(HibernateInsertSupport.holdsPlans(Plans.class, new Plans("plan"))).isTrue();
        // The field read from a generator of another class
        assertThat(HibernateInsertSupport.holdsPlans(CompositeNestedGeneratedValueGenerator.class, new NoPlans()))
                .isTrue();
    }

    /** A generator extending Hibernate's sequence generator with key code of its own. */
    static final class CustomSequence extends SequenceStyleGenerator {
    }

    @Test
    void ac_vnd_11_a_sequence_generator_subclass_is_reported_as_another_generator() {
        String name = CustomSequence.class.getName();
        assertThat(HibernateInsertSupport.idGeneration(new CustomSequence(), name))
                .isEqualTo(new IdGeneration.Other(name));
    }
}
