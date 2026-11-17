package org.example.audit;

import java.util.function.Supplier;

public final class QuoteService {
    private QuoteService() {}

    public static <T> T findOpen(Supplier<T> query) {
        return query.get();
    }
}
