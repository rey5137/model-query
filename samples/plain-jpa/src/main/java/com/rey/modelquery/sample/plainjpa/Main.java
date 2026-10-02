package com.rey.modelquery.sample.plainjpa;

/**
 * Plain JPA sample entry point: seeds a small shop and prints what the generated query models read from it.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        ShopTour.Result tour = ShopTour.run();

        System.out.println("Paid orders, page 1 of " + tour.paidPage().total().orElse(0) + ":");
        for (OrderView order : tour.paidPage().content()) {
            System.out.println("  #" + order.getId() + " " + order.getTotal() + " "
                    + order.getCustomer().map(CustomerView::name).orElse("(walk-in)"));
        }
        System.out.println("Orders with a keyboard:");
        for (OrderView order : tour.withKeyboard()) {
            System.out.println("  #" + order.getId() + " " + order.getStatus());
        }
        System.out.println("Orders with their items, loaded by a fetch plan:");
        for (OrderWithItems order : tour.withItems()) {
            System.out.println("  #" + order.id() + " " + order.items().stream().map(ItemView::sku).toList());
        }
        System.out.println("Per status:");
        for (OrderTotals totals : tour.totals()) {
            System.out.println("  " + totals.status() + ": " + totals.orders() + " orders, " + totals.revenue());
        }
    }
}
