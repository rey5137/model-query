package com.rey.modelquery.spike.rfc0024;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * RFC 0024 spike schema: orders keep their customer and referrer as plain columns (Part A and C) and also map them as
 * read-only associations (Part B nests over an association {@code @Join}). No foreign key constraints, so a key can
 * point at a missing row.
 */
final class Entities {
    private Entities() {}

    static final Class<?>[] ALL = {SpOrder.class, SpCustomer.class, SpRegion.class, SpCountry.class, SpPayment.class};

    @Entity(name = "SpOrder")
    @Table(name = "sp_order")
    public static class SpOrder {
        @Id public Long id;
        public String status;
        @Column(name = "customer_id") public Long customerId;
        @Column(name = "referrer_id") public Long referrerId;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "customer_id", insertable = false, updatable = false,
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        public SpCustomer customer;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "referrer_id", insertable = false, updatable = false,
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        public SpCustomer referrer;
    }

    @Entity(name = "SpCustomer")
    @Table(name = "sp_customer")
    public static class SpCustomer {
        @Id public Long id;
        public String name;
        public String email;
        @Column(name = "region_id") public Long regionId;
    }

    @Entity(name = "SpRegion")
    @Table(name = "sp_region")
    public static class SpRegion {
        @Id public Long id;
        public String name;
        @Column(name = "country_id") public Long countryId;
    }

    @Entity(name = "SpCountry")
    @Table(name = "sp_country")
    public static class SpCountry {
        @Id public Long id;
        public String name;
        public String code;
    }

    @Entity(name = "SpPayment")
    @Table(name = "sp_payment")
    public static class SpPayment {
        @Id public Long id;
        @Column(name = "customer_id") public Long customerId;
        @Column(precision = 12, scale = 2) public BigDecimal amount;
    }

    /**
     * The data set. Customer 11 has every column NULL but its id; order 2 points at a missing customer (999), order 3
     * at none; customer 13's region is missing, region 102's country is missing, country 3 has a NULL name. Customer
     * and referrer differ on most orders, live in different regions, order 9 has the same person twice, and
     * payment 9 belongs to the missing customer 999 (a dangling key).
     */
    static void seed(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("insert into sp_country (id, name, code) values (1, 'Norway', 'NO'), (2, 'Chile', 'CL'), "
                    + "(3, null, 'XX')");
            st.execute("insert into sp_region (id, name, country_id) values (100, 'North', 1), (101, 'South', 2), "
                    + "(102, 'Orphan', 99), (103, null, 3)");
            st.execute("insert into sp_customer (id, name, email, region_id) values "
                    + "(10, 'Ann', 'ann@x', 100), (11, null, null, null), (12, 'Bob', 'bob@x', 101), "
                    + "(13, 'Cid', 'cid@x', 999), (14, 'Dee', 'dee@x', 102), (15, 'Eve', 'eve@x', 103)");
            st.execute("insert into sp_order (id, status, customer_id, referrer_id) values "
                    + "(1, 'PAID', 10, 12), (2, 'OPEN', 999, 10), (3, 'OPEN', null, null), (4, 'PAID', 11, 13), "
                    + "(5, 'PAID', 12, 10), (6, 'OPEN', 13, 14), (7, 'PAID', 14, 15), (8, 'OPEN', 15, null), "
                    + "(9, 'PAID', 10, 10), (10, 'OPEN', 12, 999), (11, 'PAID', 15, 12), (12, 'OPEN', 14, 11)");
            st.execute("insert into sp_payment (id, customer_id, amount) values "
                    + "(1, 10, 5.00), (2, 10, 7.50), (3, 12, 3.25), (4, 13, 1.00), (5, 13, 2.00), (6, 13, 4.00), "
                    + "(7, 14, 10.00), (8, 15, 0.50), (9, 999, 99.00)");
        }
    }
}
