package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import java.util.List;
import java.util.OptionalInt;

/**
 * What key-based reads and writes share: a row's key, the {@code IN} predicate over keys, and the most keys one
 * statement takes within the profile's limits. Immutable.
 *
 * @implSpec R-PAG-03, R-PAG-07, R-WRT-08, D-32, D-63
 */
final class Keys {

    private final int maxInListSize;
    private final int maxBindParameters;

    /** Clamps to the profile's IN-list and bind-parameter limits (R-VND-06). */
    Keys(int maxInListSize, int maxBindParameters) {
        this.maxInListSize = maxInListSize;
        this.maxBindParameters = maxBindParameters;
    }

    /**
     * The most keys one statement takes: {@code configured}, if any, within the IN-list limit, and within the
     * bind-parameter limit once the statement's {@code ownBinds} are bound, at one bind per key column (R-PAG-07,
     * R-WRT-08, D-32). At least one, so a statement that alone passes the bind limit fails in the database as it
     * would unsplit.
     */
    int clamp(int ownBinds, int keyColumns, OptionalInt configured) {
        int clamp = Math.min(maxInListSize, (maxBindParameters - ownBinds) / keyColumns);
        return Math.max(1, Math.min(configured.orElse(Integer.MAX_VALUE), clamp));
    }

    /**
     * The row's primary key, read from the {@code Row} so it works for any model (R-PAG-03): the value of a
     * single-column key, the list of values of a composite one. Each is the attribute's value, before any converter,
     * so two keys a converter maps to one model value stay two keys (R-COL-11).
     *
     * @param label what the key belongs to, for the message
     * @throws ModelQueryExecutionException {@code MQ2201} when a key column is {@code null}
     */
    static <M> Object keyOf(Object label, PrimaryKey<M, ?> key, Row row) {
        List<ColumnField<M, ?, ?>> columns = key.columns();
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.raw(columns.get(i));
            if (values[i] == null) {
                // A null key cannot tell this row from another, so the boundary dedupe could drop or keep it wrongly,
                // and step 2 of primary-key-first paging could not read the row back.
                throw new ModelQueryExecutionException(MqCode.MQ2201, label + ": primary-key column "
                        + columns.get(i).name() + " is null in a row; export and primary-key-first paging need a key "
                        + "that identifies every row");
            }
        }
        return values.length == 1 ? values[0] : List.of(values);
    }
}
