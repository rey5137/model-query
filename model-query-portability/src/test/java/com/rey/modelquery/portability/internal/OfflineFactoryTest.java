package com.rey.modelquery.portability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.portability.Target;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class OfflineFactoryTest {

    /** A third-party provider support written before {@code offlineProperties}: it keeps the empty default. */
    private static final class NoOfflineSupport implements ProviderSupport {
        @Override
        public boolean supports(EntityManagerFactory emf) {
            return true;
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }
    }

    @Test
    void ac_ins_10_a_provider_support_without_offline_properties_is_mq4013() {
        OfflineUnit unit = OfflineUnit.ofClasses(List.of(String.class), List.of(), Map.of());
        assertThatThrownBy(() -> OfflineFactory.boot(unit, Target.of(DatabaseVendor.H2, "2.3.232"),
                List.of(new NoOfflineSupport())))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4013))
                .hasMessageContaining("No ProviderSupport has offline properties for H2")
                .hasMessageContaining(NoOfflineSupport.class.getName());
        assertThat(new NoOfflineSupport().offlineProperties(DatabaseVendor.H2)).isEqualTo(Optional.empty());
    }

    @Test
    void ac_ins_10_the_dry_run_overrides_beat_the_provider_and_the_unit() {
        Target target = Target.of(DatabaseVendor.MARIADB, "10.11.6");
        DataSource stub = StubDataSource.of(target);
        OfflineUnit unit = OfflineUnit.named("orders", Map.of(
                OfflineFactory.DATABASE_ACTION, "create",
                OfflineFactory.SCRIPTS_ACTION, "create",
                OfflineFactory.TRANSACTION_TYPE, "JTA",
                "provider.flag", "unit",
                "app.naming", "kept"));
        Map<String, Object> overrides = OfflineFactory.overrides(unit, target, Map.of("provider.flag", "offline"),
                stub);
        assertThat(overrides)
                .containsEntry(OfflineFactory.DATABASE_ACTION, "none")
                .containsEntry(OfflineFactory.SCRIPTS_ACTION, "none")
                .containsEntry(OfflineFactory.TRANSACTION_TYPE, "RESOURCE_LOCAL")
                .containsEntry(OfflineFactory.SHARED_CACHE_MODE, "NONE")
                .containsEntry(OfflineFactory.NON_JTA_DATA_SOURCE, stub)
                .containsEntry(OfflineFactory.PRODUCT_NAME, "MariaDB")
                .containsEntry(OfflineFactory.PRODUCT_VERSION, "10.11.6")
                .containsEntry(OfflineFactory.MAJOR_VERSION, "10")
                .containsEntry(OfflineFactory.MINOR_VERSION, "11")
                .containsEntry("provider.flag", "offline")
                .containsEntry("app.naming", "kept");
        Map<String, Object> minimum = OfflineFactory.overrides(unit, Target.of(DatabaseVendor.H2), Map.of(), stub);
        assertThat(minimum).containsEntry(OfflineFactory.PRODUCT_NAME, "H2")
                .doesNotContainKeys(OfflineFactory.PRODUCT_VERSION, OfflineFactory.MAJOR_VERSION,
                        OfflineFactory.MINOR_VERSION);
    }

    @Test
    void ac_ins_10_units_are_equal_by_name_or_sorted_classes_mapping_files_and_properties() {
        OfflineUnit one = OfflineUnit.ofClasses(List.of(String.class, Integer.class), List.of("orm.xml"),
                Map.of("k", "v"));
        assertThat(one).isEqualTo(OfflineUnit.ofClasses(List.of(Integer.class, String.class), List.of("orm.xml"),
                Map.of("k", "v")));
        assertThat(one.classNames()).containsExactly("java.lang.Integer", "java.lang.String");
        assertThat(one).isNotEqualTo(OfflineUnit.ofClasses(List.of(Integer.class, String.class), List.of(),
                Map.of("k", "v")));
        assertThat(OfflineUnit.named("orders", Map.of())).isNotEqualTo(OfflineUnit.named("orders", Map.of("k", 1)));
    }
}
