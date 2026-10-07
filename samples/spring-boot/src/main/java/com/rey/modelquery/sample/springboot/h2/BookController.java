package com.rey.modelquery.sample.springboot.h2;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.SubSelect;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Creating and partially updating books, and the h2-side endpoints of recipes 1 and 2. */
@RestController
class BookController {

    /** The model {@code POST /books} answers with, built from the persisted entity rather than read back. */
    private static final ModelQuery<BookEntity, Long, SavedBook> SAVED = QSavedBook.query()
            .select(QSavedBook.ALL)
            .build();

    private final BookRepository books;
    private final ReviewRepository reviews;
    private final BookSearchService search;

    BookController(BookRepository books, ReviewRepository reviews, BookSearchService search) {
        this.books = books;
        this.reviews = reviews;
        this.search = search;
    }

    /**
     * Creates one book through JPA, so lifecycle callbacks and Bean Validation run, and answers {@code 201} with its
     * location and the book as persisted, {@code updatedAt} included, with no query after the insert (R-WRT-48). The
     * repository opens the transaction {@code persist} needs (R-SPR-10).
     */
    @PostMapping("/books")
    ResponseEntity<SavedBook> create(@RequestBody NewBook book) {
        SavedBook saved = books.persist(QNewBook.persist(book), SAVED);
        return ResponseEntity.created(URI.create("/books/" + saved.id())).body(saved);
    }

    /** Writes the fields of {@code changes} to book {@code id}; a field sent as {@code null} is written as NULL. */
    @PatchMapping("/books/{id}")
    ResponseEntity<Void> patch(@PathVariable long id, @Valid @RequestBody BookPatchChanges changes) {
        if (changes.isEmpty()) {
            // An empty body writes nothing, which says nothing about the row (R-WRT-07).
            return books.existsById(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        }
        long written = books.update(QBookPatch.update(changes).whereKey(id).build());
        return written == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    /** A keyset page of books: {@code after} or {@code before} names a cursor, neither is the first page. */
    // DECIDE: with both cursors sent, after wins; a size outside KeysetSpec's range is left to it (MQ2001), no clamp.
    @GetMapping("/books/pages")
    BookSearchService.BookPage pages(@RequestParam(required = false) String after,
            @RequestParam(required = false) String before, @RequestParam(defaultValue = "20") int size) {
        return search.page(Optional.ofNullable(after), Optional.ofNullable(before), size);
    }

    /**
     * Recipe 1: the book read by a model query on {@code BookRepository}, and a review refreshed through the sample's
     * own repository base method on the plain {@code ReviewRepository}.
     */
    @GetMapping("/books/{id}/detail")
    BookDetail detail(@PathVariable long id, @RequestParam long reviewId) {
        ReviewEntity review = reviews.refreshAndGet(reviewId);
        return new BookDetail(search.byId(id), review == null ? null
                : new ReviewRow(review.id(), review.bookId(), review.rating()));
    }

    /** Recipe 1's response: a model-query book and a base-method review. */
    record BookDetail(BookView book, ReviewRow review) {}

    /** Recipe 1's review row. */
    record ReviewRow(Long id, Long bookId, Integer rating) {}

    /**
     * Recipe 2: books whose id is {@code in} (or, with {@code mode=notIn}, not in) the sub-select of reviewed book ids
     * with at least {@code minRating}, built per request (D-112).
     */
    @GetMapping("/books/by-review")
    List<BookView> byReview(@RequestParam int minRating, @RequestParam(defaultValue = "in") String mode) {
        SubSelect<ReviewView, Long> reviewed = SubSelect.of(QReviewView.BOOK_ID)
                .where(f -> f.gte(QReviewView.RATING, minRating));
        var query = QBookView.query()
                .select(QBookView.ALL)
                .where(f -> "notIn".equals(mode) ? f.notIn(QBookView.ID, reviewed) : f.in(QBookView.ID, reviewed))
                .orderBy(QBookView.ID.asc())
                .build();
        return books.findAll(query, Limit.unlimited());
    }

    /** Recipe 2: books with a matching review, through a correlated {@code exists} on an unmapped root (D-112). */
    @GetMapping("/books/with-review")
    List<BookView> withReview(@RequestParam int minRating) {
        SubSelect<ReviewView, Long> reviewed = SubSelect.of(QReviewView.BOOK_ID)
                .where(f -> f.gte(QReviewView.RATING, minRating));
        var query = QBookView.query()
                .select(QBookView.ALL)
                .where(f -> f.exists(reviewed,
                        (inner, outer) -> inner.compare(QReviewView.BOOK_ID, Op.EQ, outer.column(QBookView.ID))))
                .orderBy(QBookView.ID.asc())
                .build();
        return books.findAll(query, Limit.unlimited());
    }

    /** Each failed constraint as {@code field -> message}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(),
                error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }
}
