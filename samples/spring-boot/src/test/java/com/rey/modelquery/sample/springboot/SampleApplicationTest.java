package com.rey.modelquery.sample.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelUpdate;
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
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Starts the application over H2 plus PostgreSQL and MySQL in Testcontainers (needs Docker). */
class SampleApplicationTest {

    /** The timeouts each vendor's profile was asked to apply, so a query shows which profile its repository used. */
    private static final Map<DatabaseVendor, List<Duration>> TIMEOUTS = new EnumMap<>(DatabaseVendor.class);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    /** Two keys per chunk, each chunk in a transaction of its own. */
    private static final ChunkOptions EACH_CHUNK_OF_TWO = ChunkOptions.size(2).commitEachChunk();

    @BeforeAll
    static void startDatabases() {
        POSTGRES.start();
        MYSQL.start();
    }

    @AfterAll
    static void stopDatabases() {
        POSTGRES.stop();
        MYSQL.stop();
    }

    /** The sample application, with {@code sources} and {@code args} added, over the containers. */
    private static ConfigurableApplicationContext startSample(List<Class<?>> sources, String... args) {
        List<Class<?>> all = new ArrayList<>(List.of(SampleApplication.class));
        all.addAll(sources);
        List<String> arguments = new ArrayList<>(List.of(args));
        arguments.addAll(List.of("--sample.postgres.url=" + POSTGRES.getJdbcUrl(),
                "--sample.postgres.username=" + POSTGRES.getUsername(),
                "--sample.postgres.password=" + POSTGRES.getPassword(),
                "--sample.mysql.url=" + MYSQL.getJdbcUrl(),
                "--sample.mysql.username=" + MYSQL.getUsername(),
                "--sample.mysql.password=" + MYSQL.getPassword()));
        return new SpringApplicationBuilder(all.toArray(Class<?>[]::new)).web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
    }

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
        TIMEOUTS.clear();
        try (ConfigurableApplicationContext context = startSample(List.of(RecordingProfiles.class),
                "--modelquery.query-timeout=5s")) {
            // Nothing sets modelquery.vendor: each factory detects its own database (R-SPR-13).
            assertThat(vendorOf(context, "h2EntityManagerFactory")).isEqualTo(DatabaseVendor.H2);
            assertThat(vendorOf(context, "postgresEntityManagerFactory")).isEqualTo(DatabaseVendor.POSTGRESQL);
            assertThat(vendorOf(context, "mysqlEntityManagerFactory")).isEqualTo(DatabaseVendor.MYSQL);

            var books = context.getBean(BookRepository.class);
            books.saveAll(List.of(new BookEntity(1L, "Dune", 1965), new BookEntity(2L, "Emma", 1815)));
            var bookQuery = QBookView.query().select(QBookView.ALL).orderBy(QBookView.ID.asc()).build();
            assertThat(books.findAll(bookQuery, Limit.unlimited())).extracting(BookView::title)
                    .containsExactly("Dune", "Emma");
            assertThat(TIMEOUTS).containsOnlyKeys(DatabaseVendor.H2);

            var films = context.getBean(FilmRepository.class);
            films.saveAll(List.of(new FilmEntity(1L, "Alien", 1979), new FilmEntity(2L, "Heat", 1995)));
            var filmQuery = QFilmView.query().select(QFilmView.ALL).orderBy(QFilmView.ID.asc()).build();
            var filmPage = films.findPage(filmQuery, PageRequest.of(0, 1), CountMode.COUNT);
            assertThat(filmPage.getContent()).extracting(v -> v.title()).containsExactly("Alien");
            assertThat(filmPage.getTotalElements()).isEqualTo(2);
            assertThat(TIMEOUTS).containsOnlyKeys(DatabaseVendor.H2, DatabaseVendor.POSTGRESQL);

            var songs = context.getBean(SongRepository.class);
            songs.saveAll(List.of(new SongEntity(1L, "Yesterday", 1965)));
            var songQuery = QSongView.query().select(QSongView.ALL).orderBy(QSongView.ID.asc()).build();
            assertThat(songs.findAll(songQuery, Limit.unlimited())).extracting(v -> v.title())
                    .containsExactly("Yesterday");
            assertThat(TIMEOUTS).containsOnlyKeys(DatabaseVendor.H2, DatabaseVendor.POSTGRESQL,
                    DatabaseVendor.MYSQL);
        }
    }

    @Test
    void ac_spr_09_writes_need_no_transaction_and_each_chunk_commits_on_its_own_datasource() {
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            var books = context.getBean(BookRepository.class);
            books.saveAll(LongStream.rangeClosed(1, 6).mapToObj(id -> new BookEntity(id, "Book " + id, 1990)).toList());
            assertThat(books.update(ModelUpdate.builder(QBookView.ROOT).primaryKey(QBookView.KEY)
                    .set(QBookView.RELEASED, 2000).where(f -> f.gt(QBookView.ID, 0L)).build())).isEqualTo(6);
            assertFailedThirdChunkKeepsTheFirstTwo(context, "h2DataSource", "books", () -> books.delete(
                    QBookView.delete().where(f -> f.gt(QBookView.ID, 0L)).chunked(EACH_CHUNK_OF_TWO).build()));

            var films = context.getBean(FilmRepository.class);
            films.saveAll(LongStream.rangeClosed(1, 6).mapToObj(id -> new FilmEntity(id, "Film " + id, 1990)).toList());
            assertThat(films.update(ModelUpdate.builder(QFilmView.ROOT).primaryKey(QFilmView.KEY)
                    .set(QFilmView.RELEASED, 2000).where(f -> f.gt(QFilmView.ID, 0L)).build())).isEqualTo(6);
            assertFailedThirdChunkKeepsTheFirstTwo(context, "postgresDataSource", "films", () -> films.delete(
                    QFilmView.delete().where(f -> f.gt(QFilmView.ID, 0L)).chunked(EACH_CHUNK_OF_TWO).build()));
        }
    }

    @Test
    void ac_wrt_16_the_patch_endpoint_writes_set_fields_only_and_reports_each_constraint_as_a_field_error()
            throws Exception {
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            var books = context.getBean(BookRepository.class);
            books.save(new BookEntity(1L, "Original", 1990));
            var validator = new LocalValidatorFactoryBean();
            validator.afterPropertiesSet();
            MockMvc mvc = MockMvcBuilders.standaloneSetup(context.getBean("bookController"))
                    .setValidator(validator).build();
            var jdbc = new JdbcTemplate(context.getBean("h2DataSource", DataSource.class));

            // A field left out of the body passes its @NotNull and keeps its value; a set field is written.
            mvc.perform(patch("/books/1").contentType(MediaType.APPLICATION_JSON).content("{\"released\": 2001}"))
                    .andExpect(status().isNoContent());
            assertThat(jdbc.queryForMap("select title, released from books where id = 1"))
                    .containsEntry("TITLE", "Original").containsEntry("RELEASED", 2001);

            // A field sent as null fails @NotNull, a set one over its limit fails @Size/@Max; nothing is written.
            assertThat(errors(mvc, "{\"title\": null}")).containsExactly(entry("title", "must not be null"));
            assertThat(errors(mvc, "{\"title\": \"a title that is far too long\", \"released\": 3000}"))
                    .containsOnly(entry("title", "size must be between 1 and 20"),
                            entry("released", "must be less than or equal to 2100"));
            assertThat(jdbc.queryForMap("select title, released from books where id = 1"))
                    .containsEntry("TITLE", "Original").containsEntry("RELEASED", 2001);

            // A value containing an expression is data: it is stored as sent, and never evaluated (R-WRT-21).
            mvc.perform(patch("/books/1").contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"${1+1}\"}"))
                    .andExpect(status().isNoContent());
            assertThat(jdbc.queryForObject("select title from books where id = 1", String.class)).isEqualTo("${1+1}");
            mvc.perform(patch("/books/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNoContent());
            mvc.perform(patch("/books/99").contentType(MediaType.APPLICATION_JSON).content("{\"released\": 1}"))
                    .andExpect(status().isNotFound());
        }
    }

    /** The field errors a PATCH of {@code body} to book 1 answers with a 400. */
    private static Map<String, Object> errors(MockMvc mvc, String body) throws Exception {
        String json = mvc.perform(patch("/books/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readValue(json, new TypeReference<>() {});
    }

    /**
     * Runs {@code delete}, a chunked delete of all six rows of {@code table}, two per chunk, with the fifth row held by
     * a foreign key, and checks the update before it committed and the first two chunks stay committed.
     */
    private static void assertFailedThirdChunkKeepsTheFirstTwo(ConfigurableApplicationContext context,
            String dataSource, String table, Runnable delete) {
        var jdbc = new JdbcTemplate(context.getBean(dataSource, DataSource.class));
        assertThat(jdbc.queryForList("select released from " + table, Integer.class)).containsOnly(2000).hasSize(6);
        jdbc.execute("create table " + table + "_notes (ref bigint references " + table + " (id))");
        try {
            jdbc.update("insert into " + table + "_notes (ref) values (5)");
            assertThatThrownBy(delete::run).isInstanceOfSatisfying(ChunkedWriteException.class, failure -> {
                assertThat(failure.committedRows()).isEqualTo(4);
                assertThat(failure.lastCommittedKey()).contains(4L);
                assertThat(failure.inDoubtKeys()).isEmpty();
            });
            assertThat(jdbc.queryForList("select id from " + table + " order by id", Long.class))
                    .containsExactly(5L, 6L);
        } finally {
            jdbc.execute("drop table " + table + "_notes");
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
