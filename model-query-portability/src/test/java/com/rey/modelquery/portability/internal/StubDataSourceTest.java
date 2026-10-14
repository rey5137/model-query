package com.rey.modelquery.portability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.Target;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class StubDataSourceTest {

    private final DataSource dataSource = StubDataSource.of(Target.of(DatabaseVendor.POSTGRESQL, "15.4"));

    @Test
    void ac_ins_10_a_connection_during_boot_is_recorded_and_denied() {
        try (JdbcRecording boot = JdbcRecording.boot()) {
            assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
            assertThat(boot.accesses()).containsExactly("DataSource.getConnection");
        }
        // With no recording open the stub still denies.
        assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
    }

    @Test
    void ac_ins_10_every_statement_entry_point_records_and_throws() throws SQLException {
        try (JdbcRecording call = JdbcRecording.call()) {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            assertThatThrownBy(connection::createStatement).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.prepareStatement("select 1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.prepareStatement("insert x", Statement.RETURN_GENERATED_KEYS))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.prepareStatement("update y", new String[] {"id"}))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.prepareCall("{call z()}")).isInstanceOf(SQLException.class);
            connection.commit();
            connection.rollback();
            connection.close();
            assertThat(call.statements()).containsExactly("", "", "select 1", "insert x", "update y", "{call z()}");
            assertThat(call.accesses()).first().isEqualTo("Connection.createStatement: ");
        }
    }

    @Test
    void ac_ins_10_metadata_answers_the_product_alone_and_unwrap_to_a_vendor_type_is_denied() throws SQLException {
        try (JdbcRecording call = JdbcRecording.call()) {
            Connection connection = dataSource.getConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            assertThat(metaData.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metaData.getDatabaseProductVersion()).isEqualTo("15.4");
            assertThat(metaData.getDatabaseMajorVersion()).isEqualTo(15);
            assertThat(metaData.getDatabaseMinorVersion()).isEqualTo(4);
            assertThatThrownBy(metaData::getDriverName).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> metaData.getTables(null, null, "%", null))
                    .isInstanceOf(SQLException.class);
            assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
            assertThatThrownBy(() -> connection.unwrap(Runnable.class)).isInstanceOf(SQLException.class);
            assertThatThrownBy(connection::getClientInfo).isInstanceOf(SQLException.class);
            assertThat(call.accesses()).containsExactly("DatabaseMetaData.getDriverName",
                    "DatabaseMetaData.getTables", "Connection.unwrap(java.lang.Runnable)", "Connection.getClientInfo");
            assertThat(call.statements()).isEmpty();
        }
    }

    @Test
    void ac_ins_10_with_no_version_the_metadata_version_is_denied() throws SQLException {
        DataSource minimum = StubDataSource.of(Target.of(DatabaseVendor.H2));
        try (JdbcRecording call = JdbcRecording.call()) {
            DatabaseMetaData metaData = minimum.getConnection().getMetaData();
            assertThat(metaData.getDatabaseProductName()).isEqualTo("H2");
            assertThatThrownBy(metaData::getDatabaseMajorVersion).isInstanceOf(SQLException.class);
            assertThat(call.accesses()).containsExactly("DatabaseMetaData.getDatabaseMajorVersion");
        }
    }
}
