package com.rey.modelquery.portability.internal;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ModelQuery;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

/**
 * Per-pass observation of the query whose call reached the pass's first statement, when that call runs the query's
 * fetch plan on the rows it returns, so the dry run can render the plan's child statements after it (D-224).
 *
 * @implSpec R-INS-17
 */
final class ParentQuery extends ExecutorHandler {

    /**
     * The executor methods whose first argument's fetch plan runs on the rows read; {@code count} and an
     * {@code ONLY_COUNT} page read none, and a returning write takes no plan.
     */
    private static final Set<String> RUN_PLAN = Set.of("list", "page", "one", "first", "byKeys", "export", "stream");

    private final Object delegate;
    private final JdbcRecording recording;
    private ModelQuery<?, ?, ?> query;

    ParentQuery(Object delegate, JdbcRecording recording) {
        this.delegate = delegate;
        this.recording = recording;
    }

    /** The query of the call that reached the first statement, when that call runs its fetch plan. */
    Optional<ModelQuery<?, ?, ?>> query() {
        return Optional.ofNullable(query);
    }

    @Override
    protected Object handle(Method method, Object[] args) throws Throwable {
        boolean first = recording.statements().isEmpty();
        try {
            return delegate(delegate, method, args);
        } finally {
            if (first && !recording.statements().isEmpty() && RUN_PLAN.contains(method.getName())
                    && args.length > 0 && args[0] instanceof ModelQuery<?, ?, ?> q
                    && !(args.length == 3 && args[2] == CountMode.ONLY_COUNT)) {
                query = q;
            }
        }
    }
}
