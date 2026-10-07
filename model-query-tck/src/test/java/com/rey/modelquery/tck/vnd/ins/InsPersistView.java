package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.core.ColumnConverter;
import java.util.Optional;

/**
 * What {@code persist} returns of an {@link InsPersistEntity} (R-WRT-48): the generated id, a string enum, a
 * JPA-converted flag, the amount through a model converter, the database-defaulted {@code region}, the constructor's
 * {@code origin}, the {@code @PrePersist} {@code audit}, an embeddable path and the to-one's id under an {@code INNER}
 * join.
 */
@QueryModel(root = InsPersistEntity.class)
public record InsPersistView(@PrimaryKey Long id, String code, InsPersistEntity.Status status, Boolean flagged,
        @Column(converter = AmountRef.class) String amount, String region, String origin, String audit,
        @Column(attribute = "address.city") String city,
        @Join(type = JoinKind.INNER) Optional<InsSourceRef> source) {

    /** The amount as {@code #42}. */
    public static final class AmountRef implements ColumnConverter<String, Long> {

        public static final AmountRef INSTANCE = new AmountRef();

        private AmountRef() {
        }

        @Override
        public String toModel(Long attribute) {
            return "#" + attribute;
        }

        @Override
        public Long toAttribute(String model) {
            return Long.valueOf(model.substring(1));
        }
    }
}
