package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import java.util.List;

/** A customer and its notes, keyed on its email, which the notes hold under a case-insensitive collation. */
@QueryModel(root = CustomerEntity.class)
public record CustomerNotes(@PrimaryKey Long id, String email,
        @Child(key = "email", foreignKey = "customerEmail") List<Note> notes) {}
