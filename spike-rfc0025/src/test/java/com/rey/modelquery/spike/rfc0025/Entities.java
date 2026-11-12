package com.rey.modelquery.spike.rfc0025;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * RFC 0025 spike schema. A three-level tree (customer → order → line) for the plan shapes; a parent and a child with
 * one column per key type for the placeholder cells; an {@code @EmbeddedId} and an {@code @IdClass} parent for the
 * composite cells. No row is ever read: every unit boots offline on the stub.
 */
final class Entities {
    private Entities() {}

    static final List<Class<?>> ALL = List.of(SpCustomer.class, SpOrder.class, SpLine.class, KeyParent.class,
            KeyChild.class, EmbParent.class, EmbChild.class, IdcParent.class, IdcChild.class, CodeConverter.class);

    /** An enum of two constants, one of one, and one of none. */
    enum Color { RED, GREEN }

    enum Mono { ONLY }

    enum Nothing {}

    /** A value object behind an auto-applied converter. */
    record Code(String value) {}

    @Converter(autoApply = true)
    public static class CodeConverter implements AttributeConverter<Code, String> {
        @Override
        public String convertToDatabaseColumn(Code code) {
            return code == null ? null : code.value();
        }

        @Override
        public Code convertToEntityAttribute(String value) {
            return value == null ? null : new Code(value);
        }
    }

    @Entity(name = "SpCustomer")
    @Table(name = "sp_customer")
    public static class SpCustomer {
        @Id public Long id;
        public String name;
    }

    @Entity(name = "SpOrder")
    @Table(name = "sp_order")
    public static class SpOrder {
        @Id public Long id;
        public String status;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "customer_id")
        public SpCustomer customer;
    }

    @Entity(name = "SpLine")
    @Table(name = "sp_line")
    public static class SpLine {
        @Id public Long id;
        public String product;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "order_id")
        public SpOrder order;
    }

    /** One column per key type; a child keys on the same-named column of {@link KeyChild}. */
    @Entity(name = "KeyParent")
    @Table(name = "key_parent")
    public static class KeyParent {
        @Id public Long id;
        public Integer intKey;
        public String strKey;
        public UUID uuidKey;
        public LocalDate dateKey;
        @Enumerated(EnumType.STRING) public Color colorKey;
        @Enumerated(EnumType.STRING) public Mono monoKey;
        @Enumerated(EnumType.STRING) public Nothing nothingKey;
        public Code codeKey;
        public byte[] bytesKey;
    }

    @Entity(name = "KeyChild")
    @Table(name = "key_child")
    public static class KeyChild {
        @Id public Long id;
        public Integer intKey;
        public String strKey;
        public UUID uuidKey;
        public LocalDate dateKey;
        @Enumerated(EnumType.STRING) public Color colorKey;
        @Enumerated(EnumType.STRING) public Mono monoKey;
        @Enumerated(EnumType.STRING) public Nothing nothingKey;
        public Code codeKey;
        public byte[] bytesKey;
    }

    @Embeddable
    public static class EmbKey implements Serializable {
        public String region;
        public Long no;

        public EmbKey() {}

        EmbKey(String region, Long no) {
            this.region = region;
            this.no = no;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EmbKey that && Objects.equals(region, that.region) && Objects.equals(no, that.no);
        }

        @Override
        public int hashCode() {
            return Objects.hash(region, no);
        }

        @Override
        public String toString() {
            return "EmbKey(" + region + ", " + no + ")";
        }
    }

    @Entity(name = "EmbParent")
    @Table(name = "emb_parent")
    public static class EmbParent {
        @EmbeddedId public EmbKey id;
        public String name;
    }

    @Entity(name = "EmbChild")
    @Table(name = "emb_child")
    public static class EmbChild {
        @Id public Long id;
        @Embedded
        @AttributeOverride(name = "region", column = @Column(name = "parent_region"))
        @AttributeOverride(name = "no", column = @Column(name = "parent_no"))
        public EmbKey parent;
    }

    public static class IdcKey implements Serializable {
        public String region;
        public Long no;

        @Override
        public boolean equals(Object other) {
            return other instanceof IdcKey that && Objects.equals(region, that.region) && Objects.equals(no, that.no);
        }

        @Override
        public int hashCode() {
            return Objects.hash(region, no);
        }
    }

    @Entity(name = "IdcParent")
    @Table(name = "idc_parent")
    @IdClass(IdcKey.class)
    public static class IdcParent {
        @Id public String region;
        @Id public Long no;
        public String name;
    }

    @Entity(name = "IdcChild")
    @Table(name = "idc_child")
    public static class IdcChild {
        @Id public Long id;
        public String parentRegion;
        public Long parentNo;
    }
}
