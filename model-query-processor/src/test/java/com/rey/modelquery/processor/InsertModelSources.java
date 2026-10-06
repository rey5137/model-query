package com.rey.modelquery.processor;

import javax.tools.JavaFileObject;

/** Insert models and the entities they write, as sources compiled with the processor (spec processor/31 §7). */
final class InsertModelSources {

    private InsertModelSources() {}

    static final JavaFileObject ADDRESS = ProcessorHarness.source("ins.Address", """
            package ins;

            import jakarta.persistence.Embeddable;

            @Embeddable
            public class Address {
                String city;
            }
            """);

    static final JavaFileObject CUSTOMER_ENTITY = ProcessorHarness.source("ins.CustomerEntity", """
            package ins;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
            }
            """);

    /** A root with a generated id and a version, which an insert model leaves out. */
    static final JavaFileObject ORDER_ENTITY = ProcessorHarness.source("ins.OrderEntity", """
            package ins;

            import jakarta.persistence.Embedded;
            import jakarta.persistence.Entity;
            import jakarta.persistence.GeneratedValue;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;
            import jakarta.persistence.Version;
            import java.math.BigDecimal;

            @Entity
            public class OrderEntity {
                @Id
                @GeneratedValue
                Long id;
                String externalRef;
                String status;
                BigDecimal total;
                boolean paid;
                @ManyToOne
                CustomerEntity customer;
                @Embedded
                Address address;
                @Version
                Long version;
            }
            """);

    /** A root whose id is assigned, which an insert model names with {@code @PrimaryKey}. */
    static final JavaFileObject ORDER_ARCHIVE_ENTITY = ProcessorHarness.source("ins.OrderArchiveEntity", """
            package ins;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;

            @Entity
            public class OrderArchiveEntity {
                @Id
                Long orderId;
                String status;
                BigDecimal total;
                boolean paid;
            }
            """);

    static final JavaFileObject ORDER_STATUS = ProcessorHarness.source("ins.OrderStatus", """
            package ins;

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

    /** A record insert model over a generated id: a converter, a to-one by id and an embedded path. */
    static final JavaFileObject NEW_ORDER = ProcessorHarness.source("ins.NewOrder", """
            package ins;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.InsertModel;
            import java.math.BigDecimal;

            @InsertModel(root = OrderEntity.class)
            public record NewOrder(
                    String externalRef,
                    @Column(converter = OrderStatus.Converter.class) OrderStatus status,
                    BigDecimal total,
                    @Column(attribute = "customer") Long customerId,
                    @Column(attribute = "address.city") String city) {}
            """);

    /** A class insert model over an assigned id, read through its getters: {@code isPaid()} for a primitive flag. */
    static final JavaFileObject ORDER_ARCHIVE_ROW = ProcessorHarness.source("ins.OrderArchiveRow", """
            package ins;

            import com.rey.modelquery.annotations.InsertModel;
            import com.rey.modelquery.annotations.PrimaryKey;

            @InsertModel(root = OrderArchiveEntity.class)
            public class OrderArchiveRow {
                @PrimaryKey
                private Long orderId;
                private String status;
                private boolean paid;

                public OrderArchiveRow(Long orderId, String status, boolean paid) {
                    this.orderId = orderId;
                    this.status = status;
                    this.paid = paid;
                }

                public Long getOrderId() {
                    return orderId;
                }

                public String getStatus() {
                    return status;
                }

                public boolean isPaid() {
                    return paid;
                }
            }
            """);

    /** The source of an insert-select into {@code OrderArchiveEntity}. */
    static final JavaFileObject ORDER_VIEW = ProcessorHarness.source("ins.OrderView", """
            package ins;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.math.BigDecimal;

            @QueryModel(root = OrderEntity.class)
            public record OrderView(@PrimaryKey Long id, String status, BigDecimal total, Boolean paid) {}
            """);

    static final JavaFileObject STOCK_ENTITY = ProcessorHarness.source("ins.StockEntity", """
            package ins;

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
            }
            """);

    static final JavaFileObject STOCK_ID = ProcessorHarness.source("ins.StockId", """
            package ins;

            import java.io.Serializable;

            public class StockId implements Serializable {
                private static final long serialVersionUID = 1L;
                Long warehouseId;
                Long productId;
            }
            """);

    /** A composite-key insert model: its key type is the {@code @IdClass}. */
    static final JavaFileObject STOCK_ROW = ProcessorHarness.source("ins.StockRow", """
            package ins;

            import com.rey.modelquery.annotations.InsertModel;
            import com.rey.modelquery.annotations.PrimaryKey;

            @InsertModel(root = StockEntity.class)
            public record StockRow(@PrimaryKey long warehouseId, @PrimaryKey long productId, int quantity) {}
            """);

    static final JavaFileObject BASE_ENTITY = ProcessorHarness.source("ins.BaseEntity", """
            package ins;

            import jakarta.persistence.Id;
            import jakarta.persistence.MappedSuperclass;

            @MappedSuperclass
            public abstract class BaseEntity<I> {
                @Id
                I id;
            }
            """);

    static final JavaFileObject TAG_ENTITY = ProcessorHarness.source("ins.TagEntity", """
            package ins;

            import jakarta.persistence.Entity;

            @Entity
            public class TagEntity extends BaseEntity<String> {
                String label;
            }
            """);

    /** An insert model whose root's id is a {@code @MappedSuperclass} type variable, resolved on the root. */
    static final JavaFileObject TAG_ROW = ProcessorHarness.source("ins.TagRow", """
            package ins;

            import com.rey.modelquery.annotations.InsertModel;
            import com.rey.modelquery.annotations.PrimaryKey;

            @InsertModel(root = TagEntity.class)
            public record TagRow(@PrimaryKey String id, String label) {}
            """);

    static final JavaFileObject[] NEW_ORDER_SOURCES =
            {ADDRESS, CUSTOMER_ENTITY, ORDER_ENTITY, ORDER_STATUS, NEW_ORDER};

    static final JavaFileObject[] ORDER_ARCHIVE_SOURCES =
            {ADDRESS, CUSTOMER_ENTITY, ORDER_ENTITY, ORDER_ARCHIVE_ENTITY, ORDER_ARCHIVE_ROW};
}
