package com.rey.modelquery.processor;

import javax.tools.JavaFileObject;

/** A source entity and the two flat models the golden files pin: a class and a record over the same root. */
final class ShopSources {

    private ShopSources() {}

    static final JavaFileObject ADDRESS = ProcessorHarness.source("shop.Address", """
            package shop;

            import jakarta.persistence.Embeddable;

            @Embeddable
            public class Address {
                String city;
            }
            """);

    static final JavaFileObject ORDER_ENTITY = ProcessorHarness.source("shop.OrderEntity", """
            package shop;

            import jakarta.persistence.Embedded;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import java.math.BigDecimal;

            @Entity
            public class OrderEntity {
                @Id
                Long id;
                String status;
                BigDecimal total;
                String notes;
                boolean paid;
                @Embedded
                Address address;
            }
            """);

    /** A class model: an initialised field, a primitive, an embedded path, a default exclusion, a non-column. */
    static final JavaFileObject ORDER_VIEW = ProcessorHarness.source("shop.OrderView", """
            package shop;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.ExcludeFromDefaults;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.annotations.Transient;
            import java.math.BigDecimal;

            @QueryModel(root = OrderEntity.class)
            public class OrderView {
                @PrimaryKey
                private Long id;
                private String status = "NEW";
                private BigDecimal total;
                @Column(attribute = "address.city")
                private String city;
                @ExcludeFromDefaults
                private String notes;
                private boolean paid;
                @Transient
                private String label = "unmapped";

                public void setId(Long id) {
                    this.id = id;
                }

                public void setStatus(String status) {
                    this.status = status;
                }

                public void setTotal(BigDecimal total) {
                    this.total = total;
                }

                public void setCity(String city) {
                    this.city = city;
                }

                public void setNotes(String notes) {
                    this.notes = notes;
                }

                public void setPaid(boolean paid) {
                    this.paid = paid;
                }
            }
            """);

    /** A record model with a primitive key and a component that is not a column. */
    static final JavaFileObject ORDER_SUMMARY = ProcessorHarness.source("shop.OrderSummary", """
            package shop;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.ExcludeFromDefaults;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import com.rey.modelquery.annotations.Transient;

            @QueryModel(root = OrderEntity.class)
            public record OrderSummary(
                    @PrimaryKey long id,
                    String status,
                    @Column(attribute = "address.city") String city,
                    @ExcludeFromDefaults String notes,
                    @Transient String label) {}
            """);
}
