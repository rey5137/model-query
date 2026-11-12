package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.TableField;
import java.util.List;
import java.util.Optional;

/**
 * Children the processor refuses (MQ3404), written as its generated {@code ChildField}s are: a {@code byte[]} key,
 * and an {@code @EmbeddedId} matched whole against an embedded column. {@code with} keeps the parent: no cell maps
 * a child.
 */
final class HandChildren {
    private HandChildren() {}

    static final ChildField<KeyParentRow, KeyChildRow> BY_BYTES = child("byBytes",
            ColumnField.of(KeyParentRow.class, TableField.root(Entities.KeyParent.class), "bytesKey", byte[].class),
            ColumnField.of(KeyChildRow.class, TableField.root(Entities.KeyChild.class), "bytesKey", byte[].class),
            QKeyChildRow::query);

    static final ChildField<EmbParentRow, EmbChildRow> BY_EMB_ID = child("byEmbId",
            ColumnField.of(EmbParentRow.class, TableField.root(Entities.EmbParent.class), "id",
                    Entities.EmbKey.class),
            ColumnField.of(EmbChildRow.class, TableField.root(Entities.EmbChild.class), "parent",
                    Entities.EmbKey.class),
            QEmbChildRow::query);

    private static <M, C> ChildField<M, C> child(String name, ColumnField<M, ?, ?> key,
            ColumnField<C, ?, ?> foreignKey, java.util.function.Supplier<ModelQuery.Builder<?, ?, C>> query) {
        return new ChildField<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ColumnField<M, ?, ?> key() {
                return key;
            }

            @Override
            public Optional<ColumnField<C, ?, ?>> foreignKey() {
                return Optional.of(foreignKey);
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return Optional.empty();
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, C> query() {
                return query.get();
            }

            @Override
            public M with(M parent, List<C> children) {
                return parent;
            }
        };
    }
}
