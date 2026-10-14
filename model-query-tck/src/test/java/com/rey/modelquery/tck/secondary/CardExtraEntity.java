package com.rey.modelquery.tck.secondary;

import com.rey.modelquery.tck.col.CardHolderEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code cards} and the secondary table {@code card_extras}, whose holder's foreign key is a
 * column of the secondary table, not of the entity's own: a bulk statement must join it (TCK AC-COL-34).
 */
@Entity
@Table(name = "cards")
@SecondaryTable(name = "card_extras", pkJoinColumns = @PrimaryKeyJoinColumn(name = "card_id"))
public class CardExtraEntity {
    @Id
    Long id;

    @ManyToOne
    @JoinColumn(name = "holder_id", table = "card_extras")
    CardHolderEntity holder;
}
