package com.rey.modelquery.processor;

import javax.tools.JavaFileObject;

/** Update models and the entities they write, as sources compiled with the processor (spec processor/31 §6). */
final class UpdateModelSources {

    private UpdateModelSources() {}

    static final JavaFileObject ADDRESS = ProcessorHarness.source("patch.Address", """
            package patch;

            import jakarta.persistence.Embeddable;

            @Embeddable
            public class Address {
                String city;
            }
            """);

    static final JavaFileObject CUSTOMER_ENTITY = ProcessorHarness.source("patch.CustomerEntity", """
            package patch;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
                String country;
            }
            """);

    static final JavaFileObject ORDER_ENTITY = ProcessorHarness.source("patch.OrderEntity", """
            package patch;

            import jakarta.persistence.Embedded;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;
            import java.math.BigDecimal;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                String orderNo;
                String status;
                BigDecimal total;
                String note;
                boolean paid;
                @ManyToOne
                CustomerEntity customer;
                @Embedded
                Address address;
            }
            """);

    static final JavaFileObject ORDER_STATUS = ProcessorHarness.source("patch.OrderStatus", """
            package patch;

            import com.rey.modelquery.core.ColumnConverter;

            public enum OrderStatus {
                NEW,
                SHIPPED;

                public static final class Converter implements ColumnConverter<OrderStatus, String> {
                    public static final Converter INSTANCE = new Converter();

                    @Override
                    public OrderStatus toModel(String attribute) {
                        return OrderStatus.valueOf(attribute);
                    }

                    @Override
                    public String toAttribute(OrderStatus model) {
                        return model.name();
                    }
                }
            }
            """);

    /** A record update model: a converter, a to-one by id, an embedded path and a filter-only column. */
    static final JavaFileObject ORDER_PATCH = ProcessorHarness.source("patch.OrderPatch", """
            package patch;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.FilterColumn;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.UpdateModel;
            import java.math.BigDecimal;

            @UpdateModel(root = OrderEntity.class)
            @FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country")
            public record OrderPatch(
                    @PrimaryKey Long id,
                    @Column(converter = OrderStatus.Converter.class) OrderStatus status,
                    BigDecimal total,
                    String note,
                    @Column(attribute = "customer") Long customerId,
                    @Column(attribute = "address.city") String city) {}
            """);

    static final JavaFileObject STOCK_ENTITY = ProcessorHarness.source("patch.StockEntity", """
            package patch;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.IdClass;

            @Entity
            @IdClass(StockId.class)
            public class StockEntity {
                @Id
                Long warehouseId;
                @Id
                Long productId;
                Integer quantity;
                boolean blocked;
            }
            """);

    static final JavaFileObject STOCK_ID = ProcessorHarness.source("patch.StockId", """
            package patch;

            import java.io.Serializable;

            public class StockId implements Serializable {
                private static final long serialVersionUID = 1L;
                Long warehouseId;
                Long productId;
            }
            """);

    /** A class update model with a composite key and primitives, which its change set boxes. */
    static final JavaFileObject STOCK_PATCH = ProcessorHarness.source("patch.StockPatch", """
            package patch;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.UpdateModel;

            @UpdateModel(root = StockEntity.class)
            public class StockPatch {
                @PrimaryKey
                private long warehouseId;
                @PrimaryKey
                private long productId;
                private int quantity;
                private boolean blocked;
            }
            """);

    static final JavaFileObject CUSTOMER_NAME = ProcessorHarness.source("patch.CustomerName", """
            package patch;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;

            @QueryModel(root = CustomerEntity.class)
            public record CustomerName(@PrimaryKey Long id, String name) {}
            """);

    /** A record query model with a change set: a key, a joined model and a filter column, none of them written. */
    static final JavaFileObject ORDER_EDIT = ProcessorHarness.source("patch.OrderEdit", """
            package patch;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.FilterColumn;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.util.Optional;

            @QueryModel(root = OrderEntity.class, generateChanges = true)
            @FilterColumn(name = "CUSTOMER_COUNTRY", path = "customer.country")
            public record OrderEdit(
                    @PrimaryKey Long id,
                    @Column(converter = OrderStatus.Converter.class) OrderStatus status,
                    String note,
                    @Column(attribute = "address.city") String city,
                    @Join(attribute = "customer") Optional<CustomerName> customer) {}
            """);

    /** A class query model with a change set, read through its getters: {@code isPaid()} for a primitive flag. */
    static final JavaFileObject PAID_VIEW = ProcessorHarness.source("patch.PaidView", """
            package patch;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;

            @QueryModel(root = OrderEntity.class, generateChanges = true)
            public class PaidView {
                @PrimaryKey
                private Long id;
                private boolean paid;
                private String note;

                public Long getId() {
                    return id;
                }

                public void setId(Long id) {
                    this.id = id;
                }

                public boolean isPaid() {
                    return paid;
                }

                public void setPaid(boolean paid) {
                    this.paid = paid;
                }

                public String getNote() {
                    return note;
                }

                public void setNote(String note) {
                    this.note = note;
                }
            }
            """);

    static final JavaFileObject[] ORDER_PATCH_SOURCES =
            {ADDRESS, CUSTOMER_ENTITY, ORDER_ENTITY, ORDER_STATUS, ORDER_PATCH};

    static final JavaFileObject[] ORDER_EDIT_SOURCES =
            {ADDRESS, CUSTOMER_ENTITY, ORDER_ENTITY, ORDER_STATUS, CUSTOMER_NAME, ORDER_EDIT, PAID_VIEW};

    static final JavaFileObject[] STOCK_PATCH_SOURCES = {STOCK_ENTITY, STOCK_ID, STOCK_PATCH};
}
