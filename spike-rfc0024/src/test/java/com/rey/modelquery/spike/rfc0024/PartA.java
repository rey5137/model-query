package com.rey.modelquery.spike.rfc0024;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SpikeCore;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spike.rfc0024.Entities.SpCustomer;
import com.rey.modelquery.spike.rfc0024.Entities.SpOrder;
import java.util.List;
import java.util.Optional;

/**
 * Part A by hand: what the processor would generate for
 *
 * <pre>
 * &#64;QueryModel(root = SpCustomer.class)
 * record Buyer(@PrimaryKey Long id, String name, String email) {}
 *
 * &#64;QueryModel(root = SpOrder.class)
 * &#64;JoinEntity(name = "buyer", entity = SpCustomer.class, on = @JoinEntity.On(column = "customerId"))
 * record OrderWithBuyer(@PrimaryKey Long id, Long customerId,
 *         &#64;Join(entityJoin = "buyer") Optional&lt;Buyer&gt; buyer) {}
 * </pre>
 *
 * The only emulated step is {@code BUYER_TABLE}: {@link SpikeCore#presentBy} stands for the RFC's relaxed
 * {@code TableField#presentBy}. One instance per join kind, since the kind is part of the {@code @JoinEntity}.
 */
final class PartA {

    record Buyer(Long id, String name, String email) {}

    record OrderWithBuyer(Long id, Long customerId, Optional<Buyer> buyer) {}

    /** {@code QBuyer}: the nested model's own generated class. */
    static final class QBuyer {
        static final TableField<SpCustomer, SpCustomer> ROOT = TableField.root(SpCustomer.class);
        static final OrderedColumnField<Buyer, SpCustomer, Long> ID =
                ColumnField.of(Buyer.class, ROOT, "id", Long.class).named("id");
        static final OrderedColumnField<Buyer, SpCustomer, String> NAME =
                ColumnField.of(Buyer.class, ROOT, "name", String.class).named("name");
        static final OrderedColumnField<Buyer, SpCustomer, String> EMAIL =
                ColumnField.of(Buyer.class, ROOT, "email", String.class).named("email");
        static final PrimaryKey<Buyer, Long> KEY = PrimaryKey.of(ID);
        static final RowMapper<Buyer> MAPPER = row -> new Buyer(row.get(ID), row.get(NAME), row.get(EMAIL));

        private QBuyer() {}
    }

    /** {@code QOrderWithBuyer}, for one join kind. */
    final TableField<SpOrder, SpOrder> root = TableField.root(SpOrder.class);
    final OrderedColumnField<OrderWithBuyer, SpOrder, Long> id =
            ColumnField.of(OrderWithBuyer.class, root, "id", Long.class).named("id");
    final OrderedColumnField<OrderWithBuyer, SpOrder, Long> customerId =
            ColumnField.of(OrderWithBuyer.class, root, "customerId", Long.class).named("customerId");
    final EntityJoin<OrderWithBuyer, SpCustomer> buyer;
    final TableField<SpCustomer, SpCustomer> buyerTable;
    final OrderedColumnField<OrderWithBuyer, SpCustomer, Long> buyerId;
    final OrderedColumnField<OrderWithBuyer, SpCustomer, String> buyerName;
    final OrderedColumnField<OrderWithBuyer, SpCustomer, String> buyerEmail;
    final SelectSet<OrderWithBuyer> all;
    final PrimaryKey<OrderWithBuyer, Long> key = PrimaryKey.of(id);
    final RowMapper<OrderWithBuyer> mapper = this::map;

    PartA(JoinKind kind) {
        buyer = EntityJoin.of("buyer", SpCustomer.class, kind, List.of(EntityJoin.on(customerId, "id")));
        buyerTable = SpikeCore.presentBy(buyer.root(), QBuyer.KEY);
        buyerId = QBuyer.ID.withTable(OrderWithBuyer.class, buyerTable);
        buyerName = QBuyer.NAME.withTable(OrderWithBuyer.class, buyerTable);
        buyerEmail = QBuyer.EMAIL.withTable(OrderWithBuyer.class, buyerTable);
        all = SelectSet.of(id, customerId, buyerId, buyerName, buyerEmail);
    }

    ModelQuery.Builder<SpOrder, Long, OrderWithBuyer> query() {
        return ModelQuery.builder(root, mapper).primaryKey(key).joinEntity(buyer);
    }

    private OrderWithBuyer map(Row row) {
        Row nested = row.scoped(buyerTable);
        return new OrderWithBuyer(row.get(id), row.get(customerId),
                nested.get(QBuyer.ID) == null ? Optional.empty() : Optional.of(QBuyer.MAPPER.map(nested)));
    }
}
