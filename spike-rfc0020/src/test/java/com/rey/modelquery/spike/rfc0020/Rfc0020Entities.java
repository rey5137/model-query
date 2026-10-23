package com.rey.modelquery.spike.rfc0020;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * LitePOS's report shape (RFC 0020 §Spike plan): business days, sales and their lines, items, customers, inventory
 * movements, plus small exact-value tables for the null-safe (E.1), average (D.1) and rounding (D.2) cells and a
 * composite-id table for F.1, and the first-by table for B.1/B.2. Public fields, field access; the spike reads them through Criteria paths only.
 */
public final class Rfc0020Entities {

    private Rfc0020Entities() {}

    public static final Class<?>[] ALL = {
        BusinessDay.class, Item.class, Customer.class, Promo.class, Sale.class, SaleLine.class,
        InventoryMovement.class, StockSnapshot.class, NullPair.class, MoneyRow.class, RoundRow.class,
        RankRow.class
    };

    @Entity
    @Table(name = "business_day")
    public static class BusinessDay {
        @Id public Long id;
        @Column(name = "business_date", nullable = false, unique = true) public LocalDate businessDate;
        @Column(name = "counted_cash", precision = 12, scale = 2) public BigDecimal countedCash;
    }

    @Entity
    @Table(name = "item")
    public static class Item {
        @Id public Long id;
        @Column(nullable = false) public String name;
        @Column(nullable = false) public String category;
        @Column(precision = 12, scale = 2) public BigDecimal price;
    }

    @Entity
    @Table(name = "customer")
    public static class Customer {
        @Id public Long id;
        public String name;
        @Column(nullable = false) public boolean vip;
        public String region;
    }

    @Entity
    @Table(name = "promo")
    public static class Promo {
        @Id public Long id;
        public String code;
        public String region;
    }

    @Entity(name = "Sale")
    @Table(name = "sale")
    public static class Sale {
        @Id public Long id;
        @Column(name = "business_date", nullable = false) public LocalDate businessDate;
        @Column(nullable = false) public String kind;
        @Column(precision = 12, scale = 2) public BigDecimal total;
        @Column(name = "cash_total", precision = 12, scale = 2) public BigDecimal cashTotal;
        @ManyToOne(fetch = FetchType.LAZY, optional = true)
        @JoinColumn(name = "customer_id")
        public Customer customer;
        @Column(name = "promo_code") public String promoCode;
        @Column(name = "item_count") public Integer itemCount;
        @Column(name = "line_count") public Long lineCount;
    }

    @Entity
    @Table(name = "sale_line")
    public static class SaleLine {
        @Id public Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "sale_id", nullable = false)
        public Sale sale;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "item_id", nullable = false)
        public Item item;
        @Column(name = "line_no") public Short lineNo;
        public Integer qty;
        @Column(precision = 12, scale = 2) public BigDecimal amount;
    }

    @Entity
    @Table(name = "inventory_movement")
    public static class InventoryMovement {
        @Id public Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "item_id", nullable = false)
        public Item item;
        @Column(name = "business_date", nullable = false) public LocalDate businessDate;
        public Integer qty;
    }

    @Embeddable
    public static class StockKey implements Serializable {
        @Column(name = "item_id") public Long itemId;
        @Column(name = "business_date") public LocalDate businessDate;

        @Override
        public boolean equals(Object o) {
            return o instanceof StockKey k && Objects.equals(itemId, k.itemId)
                    && Objects.equals(businessDate, k.businessDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId, businessDate);
        }
    }

    @Entity
    @Table(name = "stock_snapshot")
    public static class StockSnapshot {
        @EmbeddedId public StockKey id;
        public Integer qty;
    }

    /** E.1 exact rows: (x,x), (x,y), (x,null), (null,x), (null,null). */
    @Entity
    @Table(name = "null_pair")
    public static class NullPair {
        @Id public Long id;
        public String a;
        public String b;
    }

    /** D.1 exact averages: one {@code numeric(12,2)}, {@code bigint}, {@code integer} and {@code smallint} column. */
    @Entity
    @Table(name = "money_row")
    public static class MoneyRow {
        @Id public Long id;
        public Integer grp;
        @Column(precision = 12, scale = 2) public BigDecimal amount;
        @Column(name = "big_val") public Long bigVal;
        @Column(name = "int_val") public Integer intVal;
        @Column(name = "small_val") public Short smallVal;
    }

    /** D.2 rounding inputs, {@code numeric(12,3)}. */
    @Entity
    @Table(name = "round_row")
    public static class RoundRow {
        @Id public Long id;
        @Column(precision = 12, scale = 3) public BigDecimal v;
    }

    /** B.1 first-by inputs: ties on {@code k}, a NULL {@code k}, a NULL value, a converted {@code tag}. */
    @Entity(name = "RankRow")
    @Table(name = "rank_row")
    public static class RankRow {
        @Id public Long id;
        public Integer grp;
        public Integer k;
        public String v;
        @Convert(converter = TagConverter.class) @Column(length = 1) public Tag tag;
        public Boolean ok;
    }

    public enum Tag { LOW, HIGH }

    /** {@code Tag} stored as {@code L} / {@code H}. */
    public static class TagConverter implements AttributeConverter<Tag, String> {
        @Override
        public String convertToDatabaseColumn(Tag tag) {
            return tag == null ? null : tag.name().substring(0, 1);
        }

        @Override
        public Tag convertToEntityAttribute(String code) {
            return code == null ? null : "H".equals(code) ? Tag.HIGH : Tag.LOW;
        }
    }
}
