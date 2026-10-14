package com.rey.modelquery.tck.col;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code card_holders}: its card is the inverse side of {@link CardEntity#holder}, so the foreign
 * key is on the card's table and a holder without a card has none (TCK AC-COL-34).
 */
@Entity
@Table(name = "card_holders")
public class CardHolderEntity {
    @Id
    Long id;

    String name;

    @OneToOne(mappedBy = "holder")
    CardEntity card;
}
