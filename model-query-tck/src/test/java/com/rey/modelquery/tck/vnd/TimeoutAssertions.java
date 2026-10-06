package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.PersistenceException;
import jakarta.persistence.QueryTimeoutException;
import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * Shared assertion for a statement the query timeout cancelled (R-EXE-11). A test that runs its statement straight
 * through the provider bypasses the executor's translation, so it sees whatever type the running Hibernate version
 * throws.
 */
final class TimeoutAssertions {

    private TimeoutAssertions() {
    }

    /**
     * Asserts {@code call} failed because the query timeout cancelled its statement: JPA's
     * {@link QueryTimeoutException}, Hibernate's own {@code org.hibernate.QueryTimeoutException} (which a driver may
     * report with a null SQLState), or a {@link PersistenceException} whose cause chain holds a {@link SQLException}
     * with SQLState {@code 57014}. Hibernate 6.6 and 7, and the vendors, report the cancellation as one of these
     * (R-EXE-11).
     */
    static void assertCancelledByTimeout(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        if (thrown instanceof QueryTimeoutException || thrown instanceof org.hibernate.QueryTimeoutException) {
            return;
        }
        assertThat(thrown).isInstanceOf(PersistenceException.class);
        assertThat(sqlState(thrown)).isEqualTo("57014");
    }

    /** The SQLState of the first {@link SQLException} in {@code thrown}'s cause chain, or {@code null} for none. */
    private static String sqlState(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
