package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Turns a search form, every field optional, into a model query; a unit test checks the filters it chose. */
@Service
public class BookSearchService {

    private static final Limit MAX_RESULTS = Limit.of(20);

    private final BookRepository books;

    public BookSearchService(BookRepository books) {
        this.books = books;
    }

    /** One keyset page of books and the opaque cursors that reach its neighbouring pages. */
    public record BookPage(List<BookView> books, String next, String previous) {}

    /**
     * A keyset page of at most {@code size} books, ordered by release year and then id, starting after {@code after}
     * or ending before {@code before}; with neither, the first page. The cursors of the returned page walk forward
     * through {@code next} and back through {@code previous}.
     */
    public BookPage page(Optional<String> after, Optional<String> before, int size) {
        var query = QBookView.query()
                .select(QBookView.ALL)
                .orderBy(QBookView.RELEASED.asc(), QBookView.ID.asc())
                .keyset()
                .build();
        KeysetSpec keyset = after.map(cursor -> KeysetSpec.after(cursor, size))
                .or(() -> before.map(cursor -> KeysetSpec.before(cursor, size)))
                .orElseGet(() -> KeysetSpec.first(size));
        KeysetSlice<BookView> slice = books.findKeysetPage(query, keyset, Sort.unsorted());
        return new BookPage(slice.content(), slice.nextCursor().orElse(null), slice.previousCursor().orElse(null));
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
