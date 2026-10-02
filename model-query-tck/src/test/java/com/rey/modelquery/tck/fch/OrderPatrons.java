package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/**
 * An order and three {@link Patron}s: its customer, the same customer under a second alias, and its referrer, whom
 * two thirds of the orders lack. An enricher fills the note.
 */
@QueryModel(root = OrderEntity.class)
public record OrderPatrons(@PrimaryKey Long id, String status,
        @Join Optional<Patron> customer,
        @Join(attribute = "customer") Optional<Patron> buyer,
        @Join Optional<Patron> referrer,
        @Transient String note) {

    OrderPatrons withNote(String value) {
        return new OrderPatrons(id, status, customer, buyer, referrer, value);
    }
}
