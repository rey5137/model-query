package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.TableField;

/** {@code tickets * 2}: the expression {@link FilmBand} sums (R-PROC-22). */
public final class FilmTickets implements ExpressionDefinition<FilmBand, Long> {

    public static final FilmTickets INSTANCE = new FilmTickets();

    private FilmTickets() {
    }

    @Override
    public ExpressionField<FilmBand, Long> expression() {
        ColumnField<FilmBand, FilmEntity, Long> tickets =
                ColumnField.of(FilmBand.class, TableField.root(FilmEntity.class), "tickets", Long.class);
        return Expr.times(tickets, 2L);
    }
}
