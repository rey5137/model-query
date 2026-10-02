package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Turns a search form, every field optional, into a model query; a unit test checks the filters it chose. */
@Service
public class BookSearchService {

    private static final Limit MAX_RESULTS = Limit.of(20);

    private final BookRepository books;

    public BookSearchService(BookRepository books) {
        this.books = books;
    }

    /** Books whose title contains {@code title} and that were released in {@code releasedFrom} or later. */
    public List<BookView> search(Optional<String> title, Optional<Integer> releasedFrom) {
        var query = QBookView.query()
                .select(QBookView.ALL)
                .where(f -> f
                        .likeIgnoreCase(QBookView.TITLE, title, LikeMode.CONTAINS)
                        .gte(QBookView.RELEASED, releasedFrom))
                .orderBy(QBookView.TITLE.asc())
                .build();
        return books.findAll(query, MAX_RESULTS);
    }
}
