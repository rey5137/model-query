package com.rey.modelquery.sample.springboot.h2;

import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A partial update of one book: only the fields the request body sets are written. */
@RestController
class BookController {

    private final BookRepository books;

    BookController(BookRepository books) {
        this.books = books;
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

    /** Each failed constraint as {@code field -> message}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(),
                error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }
}
