package com.rey.modelquery.tck.col;

import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

/**
 * Fixture entity over {@code cards}: its optional holder is an owning {@code @OneToOne} by the holder's primary key,
 * and its issuer a {@code @NotFound} customer whose {@code issuer_id} is {@code NULL} or names no customer on some
 * rows (TCK AC-COL-34).
 */
@Entity
@Table(name = "cards")
public class CardEntity {
    @Id
    Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holder_id")
    CardHolderEntity holder;

    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "issuer_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    CustomerEntity issuer;
}
