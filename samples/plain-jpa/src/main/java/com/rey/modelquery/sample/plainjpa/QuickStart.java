package com.rey.modelquery.sample.plainjpa;

import com.rey.modelquery.core.*;
import com.rey.modelquery.jpa.*;
import jakarta.persistence.EntityManager;

/** The README flow in full: OrderView (the model) -> generated QOrderView -> a page. No Spring. */
public final class QuickStart {
    private QuickStart() {}
    public static Slice<OrderView> firstPaidPage(EntityManager em) {
        var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
        var query = QOrderView.query()
                .columns(QOrderView.ALL)
                .where(f -> f.eq(QOrderView.STATUS, "PAID"))
                .orderBy(QOrderView.ID.asc())
                .build();
        return executor.page(query, new PageSpec(0, 2), CountMode.COUNT);
    }
}
