package com.rey.modelquery.tck.proc;

/**
 * Accessors shared by a query model and an update model over one entity. The processor reads only the fields a model
 * declares, so the interface carries methods, never columns.
 */
public interface CustomerContact {
    String name();

    String country();

    /** A label both models can give, written once. */
    default String label() {
        return name() + " (" + country() + ")";
    }
}
