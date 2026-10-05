package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spring.data.ModelPage;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Recipe 3's grouped, expression-keyed report and recipe 4's expression-ordered page, over postgres films. */
@Service
public class FilmReportService {

    private final FilmRepository films;

    public FilmReportService(FilmRepository films) {
        this.films = films;
    }

    /** Recipe 3: films whose computed genre text is {@code genre}, grouped by the computed band, summing tickets. */
    public List<FilmBand> bands(String genre) {
        ExpressionField<FilmBand, String> genreText = Expr.coalesce(ColumnField.of(FilmBand.class,
                TableField.root(FilmEntity.class), "genre", String.class), "unknown");
        var query = QFilmBand.query()
                .select(QFilmBand.GROUP_KEYS.with(QFilmBand.TICKETS))
                .where(f -> f.eq(genreText, genre))
                .orderBy(QFilmBand.BAND.asc())
                .build();
        return films.findAll(query, Limit.unlimited());
    }

    /** Recipe 4: one offset page ordered by {@code tickets + 1}, ties broken by the primary key. */
    public FilmPage page(int page, int size) {
        var query = QFilmView.query()
                .select(QFilmView.ALL)
                .orderBy(ticketsPlusOne().asc())
                .build();
        ModelPage<FilmView> result = films.findPage(query, PageRequest.of(page, size), CountMode.COUNT);
        return new FilmPage(result.getContent(), result.getTotalElements());
    }

    /**
     * Recipe 4's keyset refusal: {@code keyset()} has no column to bind an expression key to, so building the query
     * throws {@code ModelQueryDefinitionException} with {@code MQ1208} (R-PAG-25).
     */
    public void keyset() {
        QFilmView.query()
                .select(QFilmView.ALL)
                .orderBy(ticketsPlusOne().asc())
                .keyset()
                .build();
    }

    private static ExpressionField<FilmView, Long> ticketsPlusOne() {
        return Expr.plus(QFilmView.TICKETS, 1L);
    }

    /** Recipe 4's page: the rows and the unpaged total. */
    public record FilmPage(List<FilmView> films, long total) {}
}
