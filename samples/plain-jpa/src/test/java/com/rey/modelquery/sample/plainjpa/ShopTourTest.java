package com.rey.modelquery.sample.plainjpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Runs the tour against H2 and checks what it found, so the sample cannot rot unnoticed. */
class ShopTourTest {

    @Test
    void theTourReadsTheSeededShopThroughTheGeneratedModels() {
        ShopTour.Result tour = ShopTour.run();

        // Three paid orders, read two at a time; the walk-in sale has no customer.
        assertThat(tour.paidPage().content()).extracting(OrderView::getId).containsExactly(1L, 2L);
        assertThat(tour.paidPage().total()).hasValue(3);
        assertThat(tour.paidPage().hasNext()).isTrue();
        assertThat(tour.paidPage().content()).allSatisfy(order -> assertThat(order.getCustomer()).isPresent());
        // Orders 1 and 3 hold a keyboard.
        assertThat(tour.withKeyboard()).extracting(OrderView::getId).containsExactly(1L, 3L);
        // Two statuses, in order.
        assertThat(tour.totals()).extracting(OrderTotals::status, OrderTotals::orders)
                .containsExactly(tuple("NEW", 2L),
                        tuple("PAID", 3L));
        assertThat(tour.totals().get(1).revenue()).isEqualByComparingTo(new BigDecimal("167.75"));
        // The fetch plan filled each order's items; the walk-in order's single item is its own.
        assertThat(tour.withItems()).extracting(OrderWithItems::id).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(tour.withItems().get(0).items()).extracting(ItemView::sku).containsExactly("KEYBOARD", "MOUSE");
        assertThat(tour.withItems().get(3).items()).extracting(ItemView::sku).containsExactly("CABLE");
    }
}
