package com.rey.modelquery.tck.vnd.ins;

import com.rey.modelquery.annotations.ExcludeFromInserts;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.time.Instant;

/**
 * One model that reads and creates an {@link InsStampedEntity} row, its inserts leaving out the {@code status} the
 * database defaults and the {@code createdAt} the caller sets with {@code set} (R-PROC-27).
 */
@QueryModel(root = InsStampedEntity.class, generateInserts = true)
public record InsStampedView(
        @PrimaryKey Long id,
        String customer,
        @ExcludeFromInserts String status,
        @ExcludeFromInserts Instant createdAt) {}
