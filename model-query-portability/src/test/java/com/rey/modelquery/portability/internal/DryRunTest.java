package com.rey.modelquery.portability.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.PersistenceContextMode;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.WriteAssignment;
import com.rey.modelquery.jpa.WriteKind;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.portability.Target;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** AC-INS-12: the harness's copy of the application's configuration, and a call's classification. */
class DryRunTest {

    /** Settings the harness does not keep as they are, and the {@code Object} methods. */
    private static final Set<String> NOT_KEPT = Set.of("vendor", "chunkTransactions", "toString", "hashCode");

    @Test
    void ac_ins_12_the_harness_keeps_every_setting_but_the_vendor_and_the_chunk_transactions() throws Exception {
        ChunkTransactions production = new ChunkTransactions() {
            @Override
            public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
                throw new AssertionError("not called");
            }
        };
        ModelQueryConfig config = ModelQueryConfig.defaults()
                .vendor(DatabaseVendor.POSTGRESQL)
                .targetVendors(DatabaseVendor.POSTGRESQL, DatabaseVendor.H2)
                .primaryKeyFirstBatchSize(64)
                .queryTimeout(Duration.ofSeconds(7))
                .mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH)
                .keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE)
                .exportPageSize(77)
                .streamFetchSize(33)
                .vendorProfiles(List.of(new Profile()))
                .persistenceContextMode(PersistenceContextMode.KEEP)
                .bulkWriteChunkSize(55)
                .conflictUpdateWhereOnAssignedColumns(true)
                .chunkTransactions(production)
                .writeAssignments(List.of(WriteAssignment.of(Object.class, "updatedBy", String.class,
                        WriteKind.UPDATE, () -> "dry-run")));

        ModelQueryConfig harness = DryRun.harnessConfig(config);

        assertThat(harness.vendor()).isEmpty();
        assertThat(harness.chunkTransactions()).isPresent().get().isNotSameAs(production);
        // Every other setting, by its public getter, so a setting added later fails here until the harness keeps it.
        int compared = 0;
        for (Method getter : ModelQueryConfig.class.getDeclaredMethods()) {
            if (Modifier.isPublic(getter.getModifiers()) && !Modifier.isStatic(getter.getModifiers())
                    && getter.getParameterCount() == 0 && getter.getReturnType() != void.class
                    && !NOT_KEPT.contains(getter.getName())) {
                assertThat(getter.invoke(harness)).as(getter.getName()).isEqualTo(getter.invoke(config))
                        .isNotEqualTo(getter.invoke(ModelQueryConfig.defaults()));
                compared++;
            }
        }
        assertThat(compared).isEqualTo(12);
        // Without a callback configured, none is added: commitEachChunk() stays the application's MQ4004.
        assertThat(DryRun.harnessConfig(ModelQueryConfig.defaults()).chunkTransactions()).isEmpty();
    }

    private static final Target H2 = Target.of(DatabaseVendor.H2);

    /** The provider root the classification is given: this package, so {@link Translation} is the provider's. */
    private static final String PROVIDER = DryRunTest.class.getPackageName() + ".";

    private static final String SQL = "select c1_0.id from customers c1_0";

    @Test
    void ac_ins_12_jdbc_other_than_a_statement_before_the_first_is_a_harness_error() {
        try (JdbcRecording recording = JdbcRecording.call()) {
            recording.denied("Connection.createBlob");
            recording.statement("Connection.prepareStatement", SQL);
            assertThat(DryRun.classify("blob", H2, false, recording, null, PROVIDER).line()).isEqualTo("blob on " + H2
                    + ": HARNESS_ERROR reached JDBC other than through a statement: [Connection.createBlob]");
        }
        // Denied after the first statement, the call is classified by its statement.
        try (JdbcRecording recording = JdbcRecording.call()) {
            recording.statement("Connection.prepareStatement", SQL);
            recording.denied("Connection.createBlob");
            assertThat(DryRun.classify("later", H2, false, recording, null, PROVIDER).outcome())
                    .isEqualTo(Outcome.REACHED);
        }
    }

    @Test
    void ac_ins_12_a_stub_denial_with_nothing_recorded_is_jdbc_reached_off_the_calling_thread() {
        try (JdbcRecording recording = JdbcRecording.call()) {
            CallResult result = DryRun.classify("async", H2, false, recording, new IllegalStateException("wrapper",
                    new StubDataSource.Denied("Dry run: Connection.prepareStatement denied")), PROVIDER);
            assertThat(result.outcome()).isEqualTo(Outcome.HARNESS_ERROR);
            assertThat(result.line()).startsWith("async on " + H2 + ": HARNESS_ERROR JDBC was reached off the calling "
                    + "thread: ");
        }
    }

    @Test
    void ac_ins_12_a_refusal_before_any_statement_is_found_in_the_cause_chain_not_by_the_top_level_type() {
        var refusal = new ModelQueryDefinitionException(MqCode.MQ1307, "Customer: too many binds");
        try (JdbcRecording recording = JdbcRecording.call()) {
            CallResult wrapped = DryRun.classify("wrapped", H2, true, recording,
                    new IllegalStateException("the application's wrapper", refusal), PROVIDER);
            assertThat(wrapped.line()).isEqualTo("wrapped on " + H2 + ": REFUSED " + refusal.getMessage());
            assertThat(wrapped.passes()).isFalse();

            CallResult translated = DryRun.classify("translated", H2, false, recording,
                    new IllegalStateException("the application's wrapper", new Translation("no such\nfunction")),
                    PROVIDER);
            assertThat(translated.line()).isEqualTo("translated on " + H2 + ": REFUSED Translation: no such function");

            CallResult other = DryRun.classify("other", H2, false, recording,
                    new IllegalArgumentException("not the library's"), PROVIDER);
            assertThat(other.line()).isEqualTo("other on " + H2
                    + ": HARNESS_ERROR java.lang.IllegalArgumentException: not the library's");
        }
    }

    @Test
    void ac_ins_12_the_stub_stop_inside_a_library_exception_is_reached_and_a_refusal_after_it_is_not() {
        try (JdbcRecording recording = JdbcRecording.call()) {
            recording.statement("Connection.prepareStatement", SQL);
            // A chunked write wraps the provider's exception, which wraps the stub's stop.
            var stop = new StubDataSource.StatementReached("Dry run: reached " + SQL);
            var chunk = new ChunkedWriteException("chunk 1 failed", 0, null, List.of(),
                    new IllegalStateException("provider", stop));
            CallResult reached = DryRun.classify("chunked", H2, true, recording, chunk, PROVIDER);
            assertThat(reached.line()).isEqualTo("chunked on " + H2 + ": REACHED (first statement only) " + SQL);
            assertThat(reached.passes()).isTrue();

            var refusal = new ModelQueryDefinitionException(MqCode.MQ1307, "Customer: too many binds");
            CallResult after = DryRun.classify("after", H2, true, recording, refusal, PROVIDER);
            assertThat(after.line()).isEqualTo("after on " + H2 + ": REFUSED_AFTER_STATEMENT " + refusal.getMessage()
                    + " after " + SQL);

            CallResult error = DryRun.classify("error", H2, false, recording, new Translation("late"), PROVIDER);
            assertThat(error.line()).startsWith("error on " + H2 + ": HARNESS_ERROR ").endsWith("late after " + SQL);
        }
    }

    /** An exception of the provider's package, as a translation error is. */
    private static final class Translation extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Translation(String message) {
            super(message);
        }
    }

    /** A supplied profile, kept by identity. */
    private static final class Profile implements VendorProfile {
        @Override
        public DatabaseVendor vendor() {
            return DatabaseVendor.H2;
        }

        @Override
        public int maxInListSize() {
            return 100;
        }

        @Override
        public int maxBindParameters() {
            return 1000;
        }

        @Override
        public int streamingFetchSize(int requested) {
            return requested;
        }

        @Override
        public void applyTimeout(Query query, Duration timeout) {
            // no timeout in a unit test
        }

        @Override
        public NullOrdering defaultAscendingNullOrdering() {
            return NullOrdering.NULLS_FIRST;
        }
    }
}
