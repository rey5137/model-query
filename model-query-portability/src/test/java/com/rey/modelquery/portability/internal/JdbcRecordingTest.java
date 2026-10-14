package com.rey.modelquery.portability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.Target;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcRecordingTest {

    private final DataSource dataSource = StubDataSource.of(Target.of(DatabaseVendor.H2, "2.3.232"));

    @Test
    void ac_ins_10_a_boot_nested_in_a_call_records_on_its_own_and_restores_the_call() throws SQLException {
        try (JdbcRecording call = JdbcRecording.call()) {
            try (JdbcRecording boot = JdbcRecording.boot()) {
                assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
                assertThat(boot.accesses()).containsExactly("DataSource.getConnection");
            }
            assertThat(JdbcRecording.current()).containsSame(call);
            assertThat(dataSource.getConnection()).isNotNull();
            assertThat(call.accesses()).isEmpty();
        }
        assertThat(JdbcRecording.current()).isEmpty();
    }

    @Test
    void ac_ins_10_a_recording_closed_out_of_order_or_twice_is_never_revived() {
        JdbcRecording outer = JdbcRecording.call();
        JdbcRecording inner = JdbcRecording.boot();
        outer.close();
        // The inner one stays current until it closes, and then nothing is: the outer one is closed.
        assertThat(JdbcRecording.current()).containsSame(inner);
        inner.close();
        assertThat(JdbcRecording.current()).isEmpty();
        inner.close();
        outer.close();
        assertThat(JdbcRecording.current()).isEmpty();
        assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
    }

    @Test
    void ac_ins_10_a_call_closed_on_another_thread_hands_out_no_connection_and_records_nothing() {
        try (JdbcRecording call = JdbcRecording.call()) {
            CompletableFuture.runAsync(call::close).join();
            assertThat(JdbcRecording.current()).isEmpty();
            assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
            assertThat(call.accesses()).isEmpty();
        }
    }

    @Test
    void ac_ins_10_a_recording_belongs_to_the_thread_that_opened_it() {
        try (JdbcRecording call = JdbcRecording.call()) {
            assertThat(CompletableFuture.supplyAsync(JdbcRecording::current).join()).isEmpty();
            assertThatThrownBy(() -> CompletableFuture.runAsync(() -> {
                try {
                    dataSource.getConnection();
                } catch (SQLException denied) {
                    throw new IllegalStateException(denied);
                }
            }).join()).hasRootCauseInstanceOf(SQLException.class);
            assertThat(call.accesses()).isEmpty();
        }
    }
}
