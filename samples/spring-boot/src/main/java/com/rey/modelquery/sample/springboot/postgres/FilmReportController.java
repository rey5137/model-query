package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recipe 3's grouped report, recipe 4's expression-ordered page and keyset refusal, and an import, over postgres
 * films.
 */
@RestController
class FilmReportController {

    private final FilmReportService reports;
    private final FilmRepository films;

    FilmReportController(FilmReportService reports, FilmRepository films) {
        this.reports = reports;
        this.films = films;
    }

    /**
     * Imports many films in one statement, skipping those whose id already exists ({@code ON CONFLICT DO NOTHING}),
     * and answers how many were written. PostgreSQL honours the named key (R-WRT-36), so no {@code anyUniqueKey()}.
     * The repository opens the transaction.
     */
    @PostMapping("/films/import")
    Imported importFilms(@RequestBody List<NewFilm> rows) {
        var insert = QNewFilm.insert(rows).onConflict(QNewFilm.ID).doNothing().build();
        return new Imported(films.insert(insert));
    }

    /** The rows {@code POST /films/import} wrote. */
    record Imported(long written) {}

    /** Recipe 3: a report grouped by a computed band, filtered on a hand-built expression (D-115). */
    @GetMapping("/films/bands")
    List<FilmBand> bands(@RequestParam String genre) {
        return reports.bands(genre);
    }

    /** Recipe 4: an offset page ordered by an expression. */
    @GetMapping("/films/by-tickets")
    FilmReportService.FilmPage byTickets(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "2") int size) {
        return reports.page(page, size);
    }

    /** Recipe 4: a keyset order by an expression, refused with {@code MQ1208} and answered as a 400. */
    @GetMapping("/films/by-tickets/keyset")
    void keyset() {
        reports.keyset();
    }

    /**
     * Map a definition failure to a 400 carrying its code; DECIDE: the body is {@code {"code": ..., "message": ...}}.
     */
    @ExceptionHandler(ModelQueryDefinitionException.class)
    ResponseEntity<Map<String, String>> refused(ModelQueryDefinitionException e) {
        return ResponseEntity.badRequest().body(Map.of("code", e.code().name(), "message", e.getMessage()));
    }
}
