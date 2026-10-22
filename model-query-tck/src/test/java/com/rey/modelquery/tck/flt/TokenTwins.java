package com.rey.modelquery.tck.flt;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.KeysetTypeEntity;
import java.util.List;
import java.util.UUID;

/**
 * A {@code keyset_types} row keyed by its {@code UUID} token, and the rows sharing it: a fetch-plan child whose round
 * keys are {@code UUID} values stored as text, which bind only typed by the mapping (AC-FLT-35). Its id filters it.
 */
@QueryModel(root = KeysetTypeEntity.class)
public record TokenTwins(@PrimaryKey UUID token, Long id, @Child(foreignKey = "token") List<TokenTwin> twins) {}
