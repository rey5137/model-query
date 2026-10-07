package com.rey.modelquery.sample.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import com.rey.modelquery.sample.springboot.h2.ProfileEntity;
import com.rey.modelquery.sample.springboot.h2.ProfileRepository;
import com.rey.modelquery.sample.springboot.h2.QBookView;
import com.rey.modelquery.sample.springboot.h2.RefreshingRepository;
import com.rey.modelquery.sample.springboot.h2.SavedBook;
import com.rey.modelquery.sample.springboot.h2.ReviewEntity;
import com.rey.modelquery.sample.springboot.h2.ReviewRepository;
import com.rey.modelquery.sample.springboot.mysql.ProfileLookupCounter;
import com.rey.modelquery.sample.springboot.mysql.QSongView;
import com.rey.modelquery.sample.springboot.mysql.SongCreditView;
import com.rey.modelquery.sample.springboot.mysql.SongEntity;
import com.rey.modelquery.sample.springboot.mysql.SongRepository;
import com.rey.modelquery.sample.springboot.mysql.SongView;
import com.rey.modelquery.sample.springboot.postgres.FilmBand;
import com.rey.modelquery.sample.springboot.postgres.FilmEntity;
import com.rey.modelquery.sample.springboot.postgres.FilmRepository;
import com.rey.modelquery.sample.springboot.postgres.FilmView;
import com.rey.modelquery.sample.springboot.postgres.QFilmView;
import com.rey.modelquery.spring.data.ModelQueryRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
    void ac_spr_09_the_post_endpoint_persists_one_book_without_a_surrounding_transaction() throws Exception {
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            MockMvc mvc = mvc(context, "bookController");
            var jdbc = new JdbcTemplate(context.getBean("h2DataSource", DataSource.class));

            // No transaction around the request: the repository opens the one persist needs.
            Instant before = Instant.now();
            String json = mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\": 7, \"title\": \"Persisted\", \"released\": 1999}"))
                    .andExpect(status().isCreated()).andExpect(header().string("Location", "/books/7"))
                    .andReturn().getResponse().getContentAsString();
            Instant after = Instant.now();
            assertThat(jdbc.queryForMap("select title, released from books where id = 7"))
                    .containsEntry("TITLE", "Persisted").containsEntry("RELEASED", 1999);

            // The answer is the persisted model, with the updatedAt the write assignment bean stamped and stored.
            SavedBook saved = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(json, SavedBook.class);
            Instant stamped = saved.updatedAt();
            assertThat(saved).isEqualTo(new SavedBook(7L, "Persisted", 1999, stamped));
            assertThat(stamped).isBetween(before, after);
            Instant stored = jdbc.queryForObject("select updatedAt from books where id = 7", OffsetDateTime.class)
                    .toInstant();
            // The column keeps microseconds; the clock may give nanoseconds.
            assertThat(Duration.between(stamped, stored).abs()).isLessThan(Duration.ofNanos(1_000));
        }
    }

    @Test
    void ac_spr_09_the_import_endpoint_inserts_films_skipping_existing_ids() throws Exception {
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            context.getBean(FilmRepository.class).save(new FilmEntity(7L, "Persisted", 1999));
            MockMvc mvc = mvc(context, "filmReportController");
            var jdbc = new JdbcTemplate(context.getBean("postgresDataSource", DataSource.class));

            // Film 7 exists, so only the two new ids are written, and the existing row is left as it was.
            mvc.perform(post("/films/import").contentType(MediaType.APPLICATION_JSON).content("""
                            [{"id": 7, "title": "Replaced", "released": 2000},
                             {"id": 8, "title": "Imported", "released": 2001},
                             {"id": 9, "title": "Also", "released": 2002}]"""))
                    .andExpect(status().isOk()).andExpect(content().string("{\"written\":2}"));
            assertThat(jdbc.queryForList("select id || ':' || title from films order by id", String.class))
                    .containsExactly("7:Persisted", "8:Imported", "9:Also");
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

    @Test
    void r_spr_13_the_patch_endpoint_stamps_updated_at_through_the_write_assignment_bean() throws Exception {
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            context.getBean(BookRepository.class).save(new BookEntity(1L, "Original", 1990));
            MockMvc mvc = mvc(context, "bookController");
            var jdbc = new JdbcTemplate(context.getBean("h2DataSource", DataSource.class));
            // A JPA save is not a model-query write: no assignment applies to it.
            assertThat(jdbc.queryForObject("select updatedAt from books where id = 1", OffsetDateTime.class))
                    .isNull();

            Instant before = Instant.now();
            mvc.perform(patch("/books/1").contentType(MediaType.APPLICATION_JSON).content("{\"released\": 2001}"))
                    .andExpect(status().isNoContent());
            Instant after = Instant.now();

            // The bulk update set updatedAt, which neither the change set nor the endpoint names; the column keeps
            // microseconds.
            Instant stored = jdbc.queryForObject("select updatedAt from books where id = 1", OffsetDateTime.class)
                    .toInstant();
            assertThat(stored).isBetween(before.minusNanos(1_000), after.plusNanos(1_000));
        }
    }

    @Test
    void ac_spr_14_the_keyset_page_endpoint_walks_forward_and_back_to_the_first_page() throws Exception {
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            var books = context.getBean(BookRepository.class);
            books.saveAll(List.of(new BookEntity(1L, "One", 2000), new BookEntity(2L, "Two", 1990),
                    new BookEntity(3L, "Three", 2000), new BookEntity(4L, "Four", 1980),
                    new BookEntity(5L, "Five", 1990), new BookEntity(6L, "Six", 2000)));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(context.getBean("bookController")).build();

            // Ordered by released year, then id: 4(1980), 2(1990), 5(1990), 1(2000), 3(2000), 6(2000).
            KeysetResponse first = getPage(mvc, "/books/pages?size=2");
            assertThat(ids(first)).containsExactly(4L, 2L);
            assertThat(first.previous()).isNull();
            assertThat(first.next()).isNotNull();

            KeysetResponse second = getPage(mvc, "/books/pages?size=2&after=" + first.next());
            assertThat(ids(second)).containsExactly(5L, 1L);
            assertThat(second.previous()).isNotNull();
            assertThat(second.next()).isNotNull();

            KeysetResponse back = getPage(mvc, "/books/pages?size=2&before=" + second.previous());
            assertThat(ids(back)).containsExactly(4L, 2L);
            assertThat(back.previous()).isNull();
            assertThat(back.next()).isNotNull();
        }
    }

    /** A keyset page the endpoint answered with, deserialized from its JSON. */
    record KeysetResponse(List<BookView> books, String next, String previous) {}

    /** The page {@code url} answers with, asserting a 200. */
    private static KeysetResponse getPage(MockMvc mvc, String url) throws Exception {
        String json = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readValue(json, KeysetResponse.class);
    }

    private static List<Long> ids(KeysetResponse page) {
        return page.books().stream().map(BookView::id).toList();
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

    @Test
    void ac_spr_02_recipe_1_own_factory_bean_and_base_class_answer_over_http() throws Exception {
        // Recipe 1 (D-111 item 1, R-SPR-02, D-113): the sample's own factory bean and repository base class.
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            var books = context.getBean(BookRepository.class);
            var reviews = context.getBean(ReviewRepository.class);
            books.save(new BookEntity(1L, "Dune", 1965));
            reviews.save(new ReviewEntity(9L, 1L, 5));

            String json = json(mvc(context, "bookController"), "/books/1/detail?reviewId=9");

            assertThat(json).contains("\"title\":\"Dune\"").contains("\"rating\":5");
            // Both repositories carry the base class; only BookRepository also carries the model-query fragment.
            assertThat(books).isInstanceOf(ModelQueryRepository.class);
            assertThat(reviews).isNotInstanceOf(ModelQueryRepository.class);
            assertThat(books).isInstanceOf(RefreshingRepository.class);
            assertThat(reviews).isInstanceOf(RefreshingRepository.class);
            // The base method works on the plain repository too: it reloads the review saved above.
            assertThat(reviews.refreshAndGet(9L).rating()).isEqualTo(5);
        }
    }

    @Test
    void ac_flt_12_recipe_2_in_and_not_in_sub_select_over_http() throws Exception {
        // Recipe 2 (D-111 item 2, R-FLT-15, R-FLT-16, D-112).
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            MockMvc mvc = reviewFixtures(context);

            assertThat(bookIds(json(mvc, "/books/by-review?minRating=4"))).containsExactly(1L, 4L);
            // Review 3's book is NULL, so a guardless notIn over it would return nothing.
            assertThat(bookIds(json(mvc, "/books/by-review?minRating=4&mode=notIn"))).containsExactly(2L, 3L);
        }
    }

    @Test
    void ac_flt_14_recipe_2_correlated_exists_over_http() throws Exception {
        // Recipe 2 (D-111 item 2, R-FLT-17, D-112): exists reading an outer column.
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            MockMvc mvc = reviewFixtures(context);

            assertThat(bookIds(json(mvc, "/books/with-review?minRating=4"))).containsExactly(1L, 4L);
        }
    }

    @Test
    void ac_agg_14_recipe_3_expression_filter_and_computed_group_key_over_http() throws Exception {
        // Recipe 3 (D-111 item 3, R-FLT-18, R-COL-19, R-AGG-13, R-PROC-21, R-PROC-22, D-115).
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            var films = context.getBean(FilmRepository.class);
            films.saveAll(List.of(new FilmEntity(1L, "A", 1980, "drama", 10L),
                    new FilmEntity(2L, "B", 1995, "drama", 20L),
                    new FilmEntity(3L, "C", 1992, null, 30L),
                    new FilmEntity(4L, "D", 2001, "comedy", 40L)));
            MockMvc mvc = mvc(context, "filmReportController");

            // coalesce(genre, 'unknown') = 'drama' keeps films 1 and 2; the band key groups them.
            List<FilmBand> rows = new ObjectMapper().readValue(json(mvc, "/films/bands?genre=drama"),
                    new TypeReference<List<FilmBand>>() {});

            assertThat(rows).containsExactly(new FilmBand("classic", 20L), new FilmBand("modern", 40L));
        }
    }

    @Test
    void ac_pag_26_recipe_4_expression_order_page_and_keyset_refusal() throws Exception {
        // Recipe 4 (D-111 item 4, R-PAG-25, AC-PAG-26, D-115): offset by an expression, keyset refused with MQ1208.
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            var films = context.getBean(FilmRepository.class);
            // Tickets 30, 10, 20, 10, 30, 20 make the expression order 2, 4, 3, 6, 1, 5 differ from the id order,
            // and the ties (10, 20 and 30) are broken by the primary key.
            films.saveAll(List.of(new FilmEntity(1L, "A", 2000, null, 30L), new FilmEntity(2L, "B", 2000, null, 10L),
                    new FilmEntity(3L, "C", 2000, null, 20L), new FilmEntity(4L, "D", 2000, null, 10L),
                    new FilmEntity(5L, "E", 2000, null, 30L), new FilmEntity(6L, "F", 2000, null, 20L)));
            MockMvc mvc = mvc(context, "filmReportController");

            FilmPage first = filmPage(mvc, "/films/by-tickets?page=0&size=2");
            assertThat(filmIds(first)).containsExactly(2L, 4L);
            assertThat(first.total()).isEqualTo(6L);
            assertThat(filmIds(filmPage(mvc, "/films/by-tickets?page=1&size=2"))).containsExactly(3L, 6L);

            String refusal = mvc.perform(get("/films/by-tickets/keyset")).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(refusal).contains("MQ1208");
        }
    }

    @Test
    void ac_fch_16_recipe_8_cross_datasource_enricher_over_http() throws Exception {
        // Recipe 8 (D-111 item 8, R-FCH-15, R-FCH-16, D-114): one enricher declaration reused across two models.
        try (ConfigurableApplicationContext context = startSample(List.of())) {
            var profiles = context.getBean(ProfileRepository.class);
            var songs = context.getBean(SongRepository.class);
            var counter = context.getBean(ProfileLookupCounter.class);
            profiles.saveAll(List.of(new ProfileEntity(1L, 7L, 1, "gold"), new ProfileEntity(2L, 8L, 2, "silver")));
            songs.saveAll(List.of(new SongEntity(1L, "One", 1965, 7L, 1), new SongEntity(2L, "Two", 2000, 8L, 2),
                    new SongEntity(3L, "Three", 2001, 9L, 1), new SongEntity(4L, "Four", 2002, 7L, 2),
                    new SongEntity(5L, "Five", 2003, null, null)));
            MockMvc mvc = mvc(context, "musicController");

            counter.reset();
            List<SongView> all = songViews(json(mvc, "/songs"));
            assertThat(all).extracting(SongView::profile).containsExactly("gold", "silver", null, null, null);
            assertThat(counter.count()).isEqualTo(2);  // batchSize(2): four distinct keys, two chunks.

            counter.reset();
            List<SongCreditView> credits = creditViews(json(mvc, "/songs/credits"));
            assertThat(credits).extracting(SongCreditView::profile)
                    .containsExactly("gold", "silver", null, null, null);
            assertThat(counter.count()).isEqualTo(2);  // the same four keys on the second model, two chunks.
        }
    }

    /** Books 1 to 4 and the reviews recipe 2's endpoints read. */
    private static MockMvc reviewFixtures(ConfigurableApplicationContext context) {
        var books = context.getBean(BookRepository.class);
        var reviews = context.getBean(ReviewRepository.class);
        books.saveAll(List.of(new BookEntity(1L, "One", 2000), new BookEntity(2L, "Two", 2000),
                new BookEntity(3L, "Three", 2000), new BookEntity(4L, "Four", 2000)));
        reviews.saveAll(List.of(new ReviewEntity(1L, 1L, 5), new ReviewEntity(2L, 2L, 2),
                new ReviewEntity(3L, null, 5), new ReviewEntity(4L, 4L, 4)));
        return mvc(context, "bookController");
    }

    private static MockMvc mvc(ConfigurableApplicationContext context, String beanName) {
        return MockMvcBuilders.standaloneSetup(context.getBean(beanName)).build();
    }

    /** The 200 body of {@code url}. */
    private static String json(MockMvc mvc, String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<Long> bookIds(String json) throws Exception {
        return new ObjectMapper().readValue(json, new TypeReference<List<BookView>>() {}).stream()
                .map(BookView::id).toList();
    }

    private static List<SongView> songViews(String json) throws Exception {
        return new ObjectMapper().readValue(json, new TypeReference<List<SongView>>() {});
    }

    private static List<SongCreditView> creditViews(String json) throws Exception {
        return new ObjectMapper().readValue(json, new TypeReference<List<SongCreditView>>() {});
    }

    private static List<Long> filmIds(FilmPage page) {
        return page.films().stream().map(FilmView::id).toList();
    }

    private static FilmPage filmPage(MockMvc mvc, String url) throws Exception {
        return new ObjectMapper().readValue(json(mvc, url), FilmPage.class);
    }

    /** Recipe 4's page body. */
    record FilmPage(List<FilmView> films, long total) {}

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

        @Override
        public boolean conflictTargetHonoured() {
            return vendor != DatabaseVendor.MYSQL;
        }
    }

    private static DatabaseVendor vendorOf(ConfigurableApplicationContext context, String factoryBean) {
        var factory = context.getBean(factoryBean, EntityManagerFactory.class);
        return VendorResolver.resolve(factory, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile().vendor();
    }
}
