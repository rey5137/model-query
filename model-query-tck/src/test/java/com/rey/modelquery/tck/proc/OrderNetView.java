package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** A class model whose one computed field is an expression over {@code total} (R-PROC-21, R-GEN-27). */
@QueryModel(root = OrderEntity.class)
public class OrderNetView {

    @PrimaryKey
    private Long id;

    @Computed(NetTotal.class)
    private BigDecimal net;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BigDecimal getNet() {
        return net;
    }

    public void setNet(BigDecimal net) {
        this.net = net;
    }
}
