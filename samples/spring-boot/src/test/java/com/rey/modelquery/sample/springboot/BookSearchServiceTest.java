package com.rey.modelquery.sample.springboot;

import static com.rey.modelquery.test.FilterMatchers.gte;
import static com.rey.modelquery.test.FilterMatchers.likeIgnoreCase;
import static com.rey.modelquery.test.QueryAssertions.assertThatQuery;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.sample.springboot.h2.BookRepository;
import com.rey.modelquery.sample.springboot.h2.BookSearchService;
import com.rey.modelquery.sample.springboot.h2.BookView;
import com.rey.modelquery.sample.springboot.h2.QBookView;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests which filters a search turned into, with no datasource and no Spring context: the repository is a stand-in
 * that records the query it is given (a mocking library's captor does the same), and {@code model-query-test} asserts
 * on it.
 */
class BookSearchServiceTest {

    private final List<ModelQuery<?, ?, BookView>> captured = new ArrayList<>();

    private final BookSearchService service = new BookSearchService((BookRepository) Proxy.newProxyInstance(
            BookSearchServiceTest.class.getClassLoader(), new Class<?>[] {BookRepository.class},
            (proxy, method, args) -> {
                captured.add((ModelQuery<?, ?, BookView>) args[0]);
                return List.of();
            }));

    @Test
    void a_form_with_both_fields_filters_on_both() {
        service.search(Optional.of("dune"), Optional.of(1960));

        assertThat(captured).hasSize(1);
        assertThatQuery(captured.get(0))
                .hasFilters(
                        gte(QBookView.RELEASED, 1960),
                        likeIgnoreCase(QBookView.TITLE, "dune", LikeMode.CONTAINS))
                .isOrderedBy(QBookView.TITLE.asc());
    }

    @Test
    void a_skipped_field_is_absent_from_the_filters() {
        service.search(Optional.empty(), Optional.of(1960));

        assertThatQuery(captured.get(0)).hasFilters(gte(QBookView.RELEASED, 1960));
    }

    @Test
    void an_empty_form_filters_on_nothing() {
        service.search(Optional.empty(), Optional.empty());

        assertThatQuery(captured.get(0)).hasNoFilters();
    }
}
