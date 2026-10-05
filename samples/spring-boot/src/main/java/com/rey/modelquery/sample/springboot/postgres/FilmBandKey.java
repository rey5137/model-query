package com.rey.modelquery.sample.springboot.postgres;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.TableField;

/** {@code release < 1990 ? 'classic' : 'modern'}: the computed group key of {@link FilmBand} (R-PROC-21). */
public final class FilmBandKey implements ExpressionDefinition<FilmBand, String> {

    public static final FilmBandKey INSTANCE = new FilmBandKey();

    private FilmBandKey() {
    }

    @Override
    public ExpressionField<FilmBand, String> expression() {
        ColumnField<FilmBand, FilmEntity, Integer> released =
                ColumnField.of(FilmBand.class, TableField.root(FilmEntity.class), "released", Integer.class);
        return Expr.cases(FilmBand.class, String.class)
                .when(f -> f.lt(released, 1990), "classic")
                .otherwise("modern");
    }
}
