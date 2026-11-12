package com.rey.modelquery.tck.portability.child;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The offline unit of the child-statement checks (AC-INS-17): a three-level tree (customer, order, line) for the plan
 * shapes, and a parent and a child with one column per key type for the placeholder rule. No row is ever read: the
 * unit only boots offline on the stub, so it has no table in the fixture schema.
 */
public final class ChildEntities {

    /** Every managed class of the unit, the converter included. */
    public static final List<Class<?>> ALL = List.of(TreeCustomerEntity.class, TreeOrderEntity.class,
            TreeLineEntity.class, KeyParentEntity.class, KeyChildEntity.class, CodeConverter.class);

    private ChildEntities() {}

    /** An enum of two constants. */
    public enum Color { RED, GREEN }

    /** An enum of one constant. */
    public enum Mono { ONLY }

    /** An enum of none. */
    public enum Nothing {}

    /** A value object behind an auto-applied converter. */
    public record Code(String value) {}

    /** Stores a {@link Code} as its string. */
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

    /** The top of the tree. */
    @Entity(name = "TreeCustomer")
    @Table(name = "tree_customer")
    public static class TreeCustomerEntity {
        @Id public Long id;
        public String name;
    }

    /** A customer's order. */
    @Entity(name = "TreeOrder")
    @Table(name = "tree_order")
    public static class TreeOrderEntity {
        @Id public Long id;
        public String status;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "customer_id")
        public TreeCustomerEntity customer;
    }

    /** An order's line. */
    @Entity(name = "TreeLine")
    @Table(name = "tree_line")
    public static class TreeLineEntity {
        @Id public Long id;
        public String product;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "order_id")
        public TreeOrderEntity order;
    }

    /** One column per key type; a child keys on the same-named column of {@link KeyChildEntity}. */
    @Entity(name = "KeyParent")
    @Table(name = "key_parent")
    public static class KeyParentEntity {
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

    /** The child of every key type. */
    @Entity(name = "KeyChild")
    @Table(name = "key_child")
    public static class KeyChildEntity {
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
}
