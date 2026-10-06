package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.PersistenceException;
import jakarta.persistence.QueryTimeoutException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** The executor's translation of a cancellation a provider reports as a plain exception (R-EXE-11, AC-EXE-09). */
class TimeoutCancellationTest {

    @Test
    void ac_exe_09_a_57014_two_levels_deep_is_translated_to_a_query_timeout_exception() {
        var cancelled = new SQLException("canceling statement due to user request",
                DefaultModelQueryExecutor.CANCELLED_SQL_STATE);
        var thrown = new PersistenceException("could not execute statement",
                new PersistenceException("nested", cancelled));

        var translated = DefaultModelQueryExecutor.timeoutCancellation(thrown, true);

        assertThat(translated).isInstanceOf(QueryTimeoutException.class).hasCause(thrown)
                .hasMessage(thrown.getMessage());
    }

    @Test
    void ac_exe_09_another_sql_state_is_returned_unchanged() {
        var thrown = new PersistenceException("could not execute statement",
                new SQLException("syntax error", "42601"));

        assertThat(DefaultModelQueryExecutor.timeoutCancellation(thrown, true)).isSameAs(thrown);
    }

    @Test
    void ac_exe_09_an_existing_query_timeout_exception_is_returned_unchanged() {
        var already = new QueryTimeoutException("timeout");

        assertThat(DefaultModelQueryExecutor.timeoutCancellation(already, true)).isSameAs(already);
    }

    @Test
    void ac_exe_09_without_a_configured_timeout_the_exception_is_returned_unchanged() {
        var thrown = new PersistenceException("could not execute statement",
                new SQLException("canceling statement due to user request",
                        DefaultModelQueryExecutor.CANCELLED_SQL_STATE));

        assertThat(DefaultModelQueryExecutor.timeoutCancellation(thrown, false)).isSameAs(thrown);
    }
}
