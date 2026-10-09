package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.tck.col.CustomerEntity;

/** Reads a referrer entity as its key, for a read-only column: no filter binds a key back to an entity. */
public final class ReferrerIdConverter implements ColumnConverter<Long, CustomerEntity> {

    public static final ReferrerIdConverter INSTANCE = new ReferrerIdConverter();

    private ReferrerIdConverter() {}

    @Override
    public Long toModel(CustomerEntity attribute) {
        return attribute.getId();
    }

    @Override
    public CustomerEntity toAttribute(Long model) {
        throw new UnsupportedOperationException("read only");
    }
}
