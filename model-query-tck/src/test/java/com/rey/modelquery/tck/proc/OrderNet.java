package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** A record model whose one computed component is an expression over {@code total} (R-PROC-21, R-GEN-27). */
@QueryModel(root = OrderEntity.class)
public record OrderNet(@PrimaryKey Long id, @Computed(RowNet.class) BigDecimal net) {}
