package com.rey.modelquery.processor;

import javax.tools.JavaFileObject;

/**
 * The sources the golden files pin: a flat class and a flat record over one root, a nested pair, and the pair with a
 * {@code @Child} back from the nested model to the outer one.
 */
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

    static final JavaFileObject COUNTRY_ENTITY = ProcessorHarness.source("shop.CountryEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CountryEntity {
                @Id
                String code;
                String name;
            }
            """);

    static final JavaFileObject CUSTOMER_ENTITY = ProcessorHarness.source("shop.CustomerEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
                String email;
                @ManyToOne
                CountryEntity country;
            }
            """);

    static final JavaFileObject INVOICE_ENTITY = ProcessorHarness.source("shop.InvoiceEntity", """
            package shop;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class InvoiceEntity {
                @Id
                Long id;
                String status;
                @ManyToOne
                CustomerEntity customer;
            }
            """);

    static final JavaFileObject INVOICE_STATUS = ProcessorHarness.source("shop.InvoiceStatus", """
            package shop;

            import com.rey.modelquery.core.ColumnConverter;

            public enum InvoiceStatus {
                DRAFT,
                SENT;

                public static final class Converter implements ColumnConverter<InvoiceStatus, String> {
                    public static final Converter INSTANCE = new Converter();

                    @Override
                    public InvoiceStatus toModel(String attribute) {
                        return InvoiceStatus.valueOf(attribute);
                    }

                    @Override
                    public String toAttribute(InvoiceStatus model) {
                        return model.name();
                    }
                }
            }
            """);

    static final JavaFileObject COUNTRY_VIEW = ProcessorHarness.source("shop.CountryView", """
            package shop;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;

            @QueryModel(root = CountryEntity.class)
            public record CountryView(@PrimaryKey String code, String name) {}
            """);

    /** A class model nested in a record, which itself nests a record. */
    static final JavaFileObject CUSTOMER_VIEW = customerView("");

    /** {@link #CUSTOMER_VIEW} with {@code extra} declared after its columns. */
    static JavaFileObject customerView(String extra) {
        return ProcessorHarness.source("shop.CustomerView", """
                package shop;

                import com.rey.modelquery.annotations.Child;
                import com.rey.modelquery.annotations.Join;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;
                import java.util.List;
                import java.util.Optional;

                @QueryModel(root = CustomerEntity.class)
                public class CustomerView {
                    @PrimaryKey
                    Long id;
                    String name;
                %s
                    @Join
                    Optional<CountryView> country;

                    public void setId(Long id) {
                        this.id = id;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }

                    public Optional<CountryView> getCountry() {
                        return country;
                    }

                    public void setCountry(Optional<CountryView> country) {
                        this.country = country;
                    }
                }
                """.formatted(extra));
    }

    /**
     * {@link #CUSTOMER_VIEW} with a to-many {@code @Child} of {@link #INVOICE_VIEW}, which joins it back: a class
     * {@code @Child} whose {@code foreignKey} crosses an association, and the mutual pair (api/15 R-FCH-03).
     */
    static final JavaFileObject CUSTOMER_WITH_INVOICES = customerView("""
                @Child(foreignKey = "customer.id")
                List<InvoiceView> invoices;

                public void setInvoices(List<InvoiceView> invoices) {
                    this.invoices = invoices;
                }
            """);

    /**
     * The outer model of the nested pair: a converted column, one association joined twice, and a to-one
     * {@code @Child} whose {@code key} crosses two associations and whose {@code foreignKey} is the child's key.
     */
    static final JavaFileObject INVOICE_VIEW = ProcessorHarness.source("shop.InvoiceView", """
            package shop;

            import com.rey.modelquery.annotations.Child;
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.JoinKind;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.util.Optional;

            @QueryModel(root = InvoiceEntity.class)
            public record InvoiceView(
                    @PrimaryKey Long id,
                    @Column(converter = InvoiceStatus.Converter.class) InvoiceStatus status,
                    @Join Optional<CustomerView> customer,
                    @Join(attribute = "customer", type = JoinKind.INNER, prefix = "BUYER")
                    Optional<CustomerView> payer,
                    @Child(key = "customer.country.code") Optional<CountryView> billingCountry) {}
            """);

    /** Everything the nested pair compiles with, the pair itself left out. */
    static final JavaFileObject[] INVOICE_SOURCES =
            {COUNTRY_ENTITY, CUSTOMER_ENTITY, INVOICE_ENTITY, INVOICE_STATUS, COUNTRY_VIEW};
}
