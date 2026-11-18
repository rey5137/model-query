package com.rey.modelquery.jpa.vendor;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** The lookup of the {@link HandOffObserver}, once per class loader (R-INS-18). */
final class HandOffLookup {

    private static final System.Logger LOG = System.getLogger(HandOffLookup.class.getName());

    private HandOffLookup() {}

    /**
     * The one observer of {@code discovered}, or {@link HandOffObserver#NONE} when there is none or it is not
     * {@linkplain HandOffObserver#active() active}. A provider that does
     * not load, or a second one, is logged once and leaves {@code NONE} in place, never thrown, as
     * {@link VendorResolver#loadable} skips a provider that does not load: a hand-off then observes nothing rather
     * than failing the application's call.
     */
    static HandOffObserver chosen(Iterable<HandOffObserver> discovered) {
        List<HandOffObserver> found = new ArrayList<>(1);
        try {
            for (HandOffObserver observer : discovered) {
                found.add(observer);
            }
        } catch (ServiceConfigurationError | LinkageError notLoadable) {
            return notLoadable(notLoadable);
        }
        if (found.size() > 1) {
            LOG.log(System.Logger.Level.WARNING, "More than one HandOffObserver is registered with ServiceLoader, so "
                    + "none observes the models handed on; keep one on the class path: {0}",
                    found.stream().map(observer -> observer.getClass().getName()).toList());
            return HandOffObserver.NONE;
        }
        try {
            return found.isEmpty() || !found.get(0).active() ? HandOffObserver.NONE : found.get(0);
        } catch (LinkageError notLoadable) {
            return notLoadable(notLoadable);
        }
    }

    private static HandOffObserver notLoadable(Throwable notLoadable) {
        LOG.log(System.Logger.Level.WARNING, () -> "A HandOffObserver does not load, so none observes the "
                + "models handed on: " + notLoadable, notLoadable);
        return HandOffObserver.NONE;
    }

    /** Initialized on the first {@link HandOffObserver#installed()}, so building an executor looks nothing up. */
    static final class Holder {

        static final HandOffObserver INSTALLED =
                chosen(ServiceLoader.load(HandOffObserver.class, HandOffObserver.class.getClassLoader()));

        private Holder() {}
    }
}
