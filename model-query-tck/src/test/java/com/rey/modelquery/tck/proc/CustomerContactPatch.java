package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.UpdateModel;
import com.rey.modelquery.tck.col.CustomerEntity;

/** The write side of {@link CustomerContact}. */
@UpdateModel(root = CustomerEntity.class)
public record CustomerContactPatch(@PrimaryKey Long id, String name, String country) implements CustomerContact {}
