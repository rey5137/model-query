package com.rey.modelquery.sample.plainjpa;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** The tour: a small shop on in-memory H2, read only through the generated {@code Q*} classes. */
public final class ShopTour {

    /** What the tour found. */
    public record Result(Slice<OrderView> paidPage, List<OrderView> withKeyboard, List<OrderTotals> totals) {}

    private ShopTour() {
    }

    /** A session factory over a fresh in-memory H2 database named {@code name}, with the shop's tables created. */
    public static SessionFactory sessionFactory(String name) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .build();
        return new Configuration()
                .addAnnotatedClass(CustomerEntity.class)
                .addAnnotatedClass(OrderEntity.class)
                .addAnnotatedClass(OrderItemEntity.class)
                .buildSessionFactory(registry);
    }

    /** Five orders: three paid, two new, one of them a walk-in sale; two of them carry a keyboard. */
    public static void seed(EntityManager em) {
        em.getTransaction().begin();
        var ann = new CustomerEntity(1L, "Ann", "DE");
        var bo = new CustomerEntity(2L, "Bo", "US");
        em.persist(ann);
        em.persist(bo);
        var orders = List.of(
                new OrderEntity(1L, "PAID", "120.00", ann),
                new OrderEntity(2L, "PAID", "35.50", bo),
                new OrderEntity(3L, "NEW", "80.00", ann),
                new OrderEntity(4L, "PAID", "12.25", null),
                new OrderEntity(5L, "NEW", "60.00", bo));
        orders.forEach(em::persist);
        long id = 1;
        for (var line : List.of(
                new Object[] {0, "KEYBOARD", 1}, new Object[] {0, "MOUSE", 2}, new Object[] {1, "MOUSE", 1},
                new Object[] {2, "KEYBOARD", 3}, new Object[] {3, "CABLE", 5}, new Object[] {4, "MOUSE", 1})) {
            em.persist(new OrderItemEntity(id++, orders.get((Integer) line[0]), (String) line[1], (Integer) line[2]));
        }
        em.getTransaction().commit();
    }

    /** Reads the shop: a filtered page, an {@code exists} filter on the items, and the summary. */
    public static Result run(EntityManager em) {
        var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());

        // A filtered page of order views, each with its customer, if any.
        var paid = QOrderView.query()
                .columns(QOrderView.ALL.with(QOrderView.CUSTOMER))
                .where(f -> f.eq(QOrderView.STATUS, "PAID"))
                .orderBy(QOrderView.ID.asc())
                .build();
        Slice<OrderView> paidPage = executor.page(paid, new PageSpec(0, 2), CountMode.COUNT);

        // The orders with a keyboard among their items, through the generated table of the collection.
        var withKeyboard = QOrderView.query()
                .columns(QOrderView.ALL)
                .where(f -> f.exists(QOrderView.ITEMS_TABLE, item -> item.eq(QOrderView.ITEM_SKU, "KEYBOARD")))
                .orderBy(QOrderView.ID.asc())
                .build();

        // Orders and revenue per status.
        var totals = QOrderTotals.query()
                .columns(QOrderTotals.GROUP_KEYS.with(QOrderTotals.ORDERS, QOrderTotals.REVENUE))
                .orderBy(QOrderTotals.STATUS.asc())
                .build();

        return new Result(paidPage, executor.list(withKeyboard, Limit.unlimited()),
                executor.list(totals, Limit.unlimited()));
    }

    /** Runs the whole tour on a fresh database. */
    public static Result run() {
        try (SessionFactory sessionFactory = sessionFactory("shop")) {
            return sessionFactory.fromSession(em -> {
                seed(em);
                return run(em);
            });
        }
    }
}
