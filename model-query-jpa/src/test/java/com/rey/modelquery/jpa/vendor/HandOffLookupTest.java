package com.rey.modelquery.jpa.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.Test;

/**
 * The {@link HandOffObserver} lookup (R-INS-18): at most one provider, a broken or second one logged once and never
 * thrown, {@code NONE} otherwise. That building an executor looks nothing up is {@code HandOffTest}'s.
 */
class HandOffLookupTest {

    private static final Logger LOG = Logger.getLogger(HandOffLookup.class.getName());

    private static final HandOffObserver ONE = (query, child, models, rows) -> {};

    private static final HandOffObserver TWO = (query, child, models, rows) -> {};

    @Test
    void r_ins_18_no_provider_is_the_no_op_default_with_nothing_logged() {
        List<String> warnings = new ArrayList<>();

        HandOffObserver chosen = capturingWarnings(warnings, () -> HandOffLookup.chosen(List.of()));

        assertThat(chosen).isSameAs(HandOffObserver.NONE);
        assertThat(warnings).isEmpty();
    }

    @Test
    void r_ins_18_one_provider_is_the_observer() {
        List<String> warnings = new ArrayList<>();

        HandOffObserver chosen = capturingWarnings(warnings, () -> HandOffLookup.chosen(List.of(ONE)));

        assertThat(chosen).isSameAs(ONE);
        assertThat(warnings).isEmpty();
    }

    @Test
    void r_ins_18_two_providers_leave_the_default_with_one_warning_naming_both() {
        List<String> warnings = new ArrayList<>();

        HandOffObserver chosen = capturingWarnings(warnings, () -> HandOffLookup.chosen(List.of(ONE, TWO)));

        assertThat(chosen).isSameAs(HandOffObserver.NONE);
        assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning)
                .contains("More than one HandOffObserver", ONE.getClass().getName(), TWO.getClass().getName()));
    }

    @Test
    void r_ins_18_a_provider_that_does_not_load_leaves_the_default_with_one_warning_and_no_throw() {
        List<String> warnings = new ArrayList<>();

        HandOffObserver chosen = capturingWarnings(warnings, () -> HandOffLookup.chosen(
                failingAfter(ONE, new ServiceConfigurationError("observer library absent"))));

        assertThat(chosen).isSameAs(HandOffObserver.NONE);
        assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning)
                .contains("A HandOffObserver does not load", "observer library absent"));
    }

    @Test
    void r_ins_18_a_provider_whose_class_does_not_link_leaves_the_default_with_one_warning_and_no_throw() {
        List<String> warnings = new ArrayList<>();

        HandOffObserver chosen = capturingWarnings(warnings, () -> HandOffLookup.chosen(
                failingAfter(null, new NoClassDefFoundError("org/example/Missing"))));

        assertThat(chosen).isSameAs(HandOffObserver.NONE);
        assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning)
                .contains("A HandOffObserver does not load", "org/example/Missing"));
    }

    /** Yields {@code first}, if any, then throws {@code error}, as a service iterator meeting a broken provider. */
    private static Iterable<HandOffObserver> failingAfter(HandOffObserver first, Error error) {
        return () -> new Iterator<>() {
            private boolean given = first == null;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public HandOffObserver next() {
                if (!given) {
                    given = true;
                    return first;
                }
                throw error;
            }
        };
    }

    private static <T> T capturingWarnings(List<String> warnings, Supplier<T> work) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING) {
                    warnings.add(new SimpleFormatter().formatMessage(r));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        LOG.addHandler(handler);
        try {
            return work.get();
        } finally {
            LOG.removeHandler(handler);
        }
    }
}
