package com.rey.modelquery.sample.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.sample.springboot.h2.BookEntity;
import com.rey.modelquery.sample.springboot.h2.BookRepository;
import com.rey.modelquery.sample.springboot.h2.BookSearchService.BookPage;
import com.rey.modelquery.sample.springboot.h2.BookView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises the books endpoint with the sample's H2 configuration, real repositories and Hibernate. */
class BookPaginationTest {

    private ConfigurableApplicationContext context;
    private BookRepository books;
    private MockMvc mvc;

    // A source for this test only, not a @Configuration discovered by the full sample's component scan.
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class})
    @ComponentScan(basePackageClasses = BookRepository.class)
    static class BooksApplication {}

    @BeforeEach
    void startBooks() {
        context = new SpringApplicationBuilder(BooksApplication.class).web(WebApplicationType.NONE)
                .run("--sample.h2.url=jdbc:h2:mem:book-pagination;DB_CLOSE_DELAY=-1");
        books = context.getBean(BookRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(context.getBean("bookController")).build();
    }

    @AfterEach
    void stopBooks() {
        if (context != null) {
            context.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 6, 7})
    void ac_pag_15_the_endpoint_walks_every_book_in_order_and_stops_at_the_last_page(int size) throws Exception {
        seedBooks();
        List<Long> expected = List.of(4L, 2L, 5L, 1L, 3L, 6L);
        List<Long> visited = new ArrayList<>();
        BookPage page = page(get("/books/pages").param("size", Integer.toString(size)));
        assertThat(page.previous()).isNull();

        while (true) {
            int from = visited.size();
            int to = Math.min(from + size, expected.size());
            assertThat(page.books()).extracting(BookView::id).containsExactlyElementsOf(expected.subList(from, to));
            visited.addAll(page.books().stream().map(BookView::id).toList());
            if (to == expected.size()) {
                assertThat(page.next()).isNull();
                break;
            }
            assertThat(page.next()).isNotBlank();
            page = page(get("/books/pages").param("size", Integer.toString(size)).param("after", page.next()));
            assertThat(page.previous()).isNotBlank();
        }

        assertThat(visited).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void ac_pag_15_omitting_size_returns_the_default_page_and_its_continuation() throws Exception {
        books.saveAll(java.util.stream.LongStream.rangeClosed(1, 21)
                .mapToObj(id -> new BookEntity(id, "Book " + id, 2000)).toList());

        BookPage first = page(get("/books/pages"));
        assertThat(first.books()).hasSize(20);
        assertThat(first.previous()).isNull();
        assertThat(first.next()).isNotBlank();
        BookPage last = page(get("/books/pages").param("after", first.next()));
        assertThat(last.books()).extracting(BookView::id).containsExactly(21L);
        assertThat(last.next()).isNull();
        List<Long> visited = new ArrayList<>(first.books().stream().map(BookView::id).toList());
        visited.addAll(last.books().stream().map(BookView::id).toList());
        assertThat(visited).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList());
    }

    @Test
    void ac_pag_21_an_empty_first_page_has_no_cursors() throws Exception {
        assertThat(page(get("/books/pages"))).isEqualTo(new BookPage(List.of(), null, null));
    }

    @Test
    void ac_pag_21_after_returns_no_cursors_when_the_remaining_rows_were_deleted() throws Exception {
        seedBooks();
        BookPage first = page(get("/books/pages").param("size", "2"));
        assertThat(first.next()).isNotBlank();
        books.deleteAllById(List.of(5L, 1L, 3L, 6L));

        assertThat(page(get("/books/pages").param("size", "2").param("after", first.next())))
                .isEqualTo(new BookPage(List.of(), null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not base64!"})
    void ac_pag_19_invalid_after_is_rejected_instead_of_restarting(String cursor) {
        assertRefused(get("/books/pages").param("after", cursor), MqCode.MQ2208);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    void ac_exe_05_invalid_sizes_are_rejected_without_clamping(int size) {
        // R-PAG-16 also rejects Integer.MAX_VALUE because keyset paging reads size + 1 rows.
        assertRefused(get("/books/pages").param("size", Integer.toString(size)), MqCode.MQ2001);
    }

    @Test
    void ac_pag_07_a_null_release_year_is_rejected_instead_of_omitted() {
        seedBooks();
        books.save(new BookEntity(7L, "Unknown", null));

        assertRefused(get("/books/pages").param("size", "2"), MqCode.MQ2202);
    }

    private void seedBooks() {
        books.saveAll(List.of(new BookEntity(1L, "One", 2000), new BookEntity(2L, "Two", 1990),
                new BookEntity(3L, "Three", 2000), new BookEntity(4L, "Four", 1980),
                new BookEntity(5L, "Five", 1990), new BookEntity(6L, "Six", 2000)));
    }

    private BookPage page(MockHttpServletRequestBuilder request) throws Exception {
        String json = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readValue(json, BookPage.class);
    }

    private void assertRefused(MockHttpServletRequestBuilder request, MqCode code) {
        assertThatThrownBy(() -> mvc.perform(request)).hasRootCauseInstanceOf(ModelQueryExecutionException.class)
                .rootCause().isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
