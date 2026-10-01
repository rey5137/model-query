package com.rey.modelquery.sample.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.sample.springboot.h2.BookEntity;
import com.rey.modelquery.sample.springboot.h2.BookRepository;
import com.rey.modelquery.sample.springboot.h2.BookView;
import com.rey.modelquery.sample.springboot.h2.QBookView;
import com.rey.modelquery.sample.springboot.mysql.QSongView;
import com.rey.modelquery.sample.springboot.mysql.SongEntity;
import com.rey.modelquery.sample.springboot.mysql.SongRepository;
import com.rey.modelquery.sample.springboot.postgres.FilmEntity;
import com.rey.modelquery.sample.springboot.postgres.FilmRepository;
import com.rey.modelquery.sample.springboot.postgres.QFilmView;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Starts the application over H2 plus PostgreSQL and MySQL in Testcontainers (needs Docker). */
class SampleApplicationTest {

    /** The timeouts each vendor's profile was asked to apply, so a query shows which profile its repository used. */
    private static final Map<DatabaseVendor, List<Duration>> TIMEOUTS = new EnumMap<>(DatabaseVendor.class);

    /** One recording profile per vendor, standing in for the built-in ones. */
    @Configuration
    static class RecordingProfiles {

        @Bean
        VendorProfile h2Profile() {
            return new RecordingProfile(DatabaseVendor.H2);
        }

        @Bean
        VendorProfile postgresProfile() {
            return new RecordingProfile(DatabaseVendor.POSTGRESQL);
        }

        @Bean
        VendorProfile mysqlProfile() {
            return new RecordingProfile(DatabaseVendor.MYSQL);
        }
    }

    @Test
    void ac_spr_02_three_datasources_resolve_one_profile_per_factory_and_each_repository_reads_its_own_database() {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine");
                var mysql = new MySQLContainer("mysql:8.4")) {
            postgres.start();
            mysql.start();
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SampleApplication.class, RecordingProfiles.class)
                    .web(WebApplicationType.NONE)
                    .run("--modelquery.query-timeout=5s", "--sample.postgres.url=" + postgres.getJdbcUrl(),
                            "--sample.postgres.username=" + postgres.getUsername(),
                            "--sample.postgres.password=" + postgres.getPassword(),
                            "--sample.mysql.url=" + mysql.getJdbcUrl(),
                            "--sample.mysql.username=" + mysql.getUsername(),
                            "--sample.mysql.password=" + mysql.getPassword())) {
                // Nothing sets modelquery.vendor: each factory detects its own database (R-SPR-13).
                assertThat(vendorOf(context, "h2EntityManagerFactory")).isEqualTo(DatabaseVendor.H2);
                assertThat(vendorOf(context, "postgresEntityManagerFactory")).isEqualTo(DatabaseVendor.POSTGRESQL);
                assertThat(vendorOf(context, "mysqlEntityManagerFactory")).isEqualTo(DatabaseVendor.MYSQL);

                var books = context.getBean(BookRepository.class);
                books.saveAll(List.of(new BookEntity(1L, "Dune", 1965), new BookEntity(2L, "Emma", 1815)));
                var bookQuery = QBookView.query().columns(QBookView.ALL).orderBy(QBookView.ID.asc()).build();
                assertThat(books.findAll(bookQuery, Limit.unlimited())).extracting(BookView::title)
                        .containsExactly("Dune", "Emma");
                assertThat(TIMEOUTS).containsOnlyKeys(DatabaseVendor.H2);

                var films = context.getBean(FilmRepository.class);
                films.saveAll(List.of(new FilmEntity(1L, "Alien", 1979), new FilmEntity(2L, "Heat", 1995)));
                var filmQuery = QFilmView.query().columns(QFilmView.ALL).orderBy(QFilmView.ID.asc()).build();
                var filmPage = films.findPage(filmQuery, PageRequest.of(0, 1), CountMode.COUNT);
                assertThat(filmPage.getContent()).extracting(v -> v.title()).containsExactly("Alien");
                assertThat(filmPage.getTotalElements()).isEqualTo(2);
                assertThat(TIMEOUTS).containsOnlyKeys(DatabaseVendor.H2, DatabaseVendor.POSTGRESQL);

                var songs = context.getBean(SongRepository.class);
                songs.saveAll(List.of(new SongEntity(1L, "Yesterday", 1965)));
                var songQuery = QSongView.query().columns(QSongView.ALL).orderBy(QSongView.ID.asc()).build();
                assertThat(songs.findAll(songQuery, Limit.unlimited())).extracting(v -> v.title())
                        .containsExactly("Yesterday");
                assertThat(TIMEOUTS).containsOnlyKeys(DatabaseVendor.H2, DatabaseVendor.POSTGRESQL,
                        DatabaseVendor.MYSQL);
            }
        }
    }

    /** Behaves as a plain profile for its vendor and records the timeouts applied through it. */
    private record RecordingProfile(DatabaseVendor vendor) implements VendorProfile {

        @Override
        public int maxInListSize() {
            return 1000;
        }

        @Override
        public int maxBindParameters() {
            return 30000;
        }

        @Override
        public void applyStreaming(Query query, int fetchSize) {
            query.setHint("org.hibernate.fetchSize", fetchSize);
        }

        @Override
        public void applyTimeout(Query query, Duration timeout) {
            TIMEOUTS.computeIfAbsent(vendor, v -> new ArrayList<>()).add(timeout);
        }

        @Override
        public NullOrdering defaultAscendingNullOrdering() {
            return vendor == DatabaseVendor.POSTGRESQL ? NullOrdering.NULLS_LAST : NullOrdering.NULLS_FIRST;
        }
    }

    private static DatabaseVendor vendorOf(ConfigurableApplicationContext context, String factoryBean) {
        var factory = context.getBean(factoryBean, EntityManagerFactory.class);
        return VendorResolver.resolve(factory, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile().vendor();
    }
}
