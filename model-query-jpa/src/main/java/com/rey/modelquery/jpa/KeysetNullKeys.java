package com.rey.modelquery.jpa;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.jpa.spi.ProviderSupport;

/**
 * What keyset paging does with a NULL in a column ordered with {@code DEFAULT} null precedence
 * ({@code modelquery.keyset.null-keys}, R-PAG-05). A column ordered {@code nullsFirst()} or {@code nullsLast()} pages
 * its NULLs either way.
 *
 * @implSpec R-PAG-05, R-COL-13
 */
@Incubating
public enum KeysetNullKeys {

    /** The default: a NULL in such a column throws {@code MQ2202} when a page reads it. */
    FAIL,

    /**
     * Such a column pages its NULLs where the database sorts them by default, as reported by the profile's
     * {@code defaultAscendingNullOrdering()}, or where the provider's configured default puts them
     * ({@link ProviderSupport#defaultNullPrecedence}, D-36); under an {@code UNKNOWN} ordering ({@code OTHER}) with
     * no provider default a NULL still throws {@code MQ2202} (R-COL-13, R-VND-06).
     */
    HONOUR_NULL_PRECEDENCE
}
