package com.rey.modelquery.spike.rfc0019;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

public final class LitePosEntities {

    private LitePosEntities() {}

    public enum SaleStatus {
        COMPLETED,
        CANCELLED,
        REFUNDED
    }

    @Converter
    public static class SaleStatusConverter implements AttributeConverter<SaleStatus, String> {
        @Override
        public String convertToDatabaseColumn(SaleStatus attribute) {
            return attribute == null ? null : attribute.name();
        }

        @Override
        public SaleStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : SaleStatus.valueOf(dbData);
        }
    }

    @Entity(name = "Store")
    @Table(name = "store")
    public static class Store {
        @Id
        private Long id;

        @Column(name = "code", nullable = false)
        private String code;

        @Column(name = "name", nullable = false)
        private String name;

        public Store() {}

        public Store(Long id, String code, String name) {
            this.id = id;
            this.code = code;
            this.name = name;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Entity(name = "Terminal")
    @Table(name = "terminal")
    public static class Terminal {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "store_id", nullable = false)
        private Store store;

        @Column(name = "serial_number", nullable = false)
        private String serialNumber;

        public Terminal() {}

        public Terminal(Long id, Store store, String serialNumber) {
            this.id = id;
            this.store = store;
            this.serialNumber = serialNumber;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Store getStore() { return store; }
        public void setStore(Store store) { this.store = store; }
        public String getSerialNumber() { return serialNumber; }
        public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    }

    @Entity(name = "Cashier")
    @Table(name = "cashier")
    public static class Cashier {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "store_id", nullable = false)
        private Store store;

        @Column(name = "code", nullable = false)
        private String code;

        @Column(name = "name", nullable = false)
        private String name;

        public Cashier() {}

        public Cashier(Long id, Store store, String code, String name) {
            this.id = id;
            this.store = store;
            this.code = code;
            this.name = name;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Store getStore() { return store; }
        public void setStore(Store store) { this.store = store; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Entity(name = "Sale")
    @Table(name = "sale")
    @FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "storeId", type = Long.class))
    @Filter(name = "tenantFilter", condition = "store_id = :storeId")
    public static class Sale {
        @Id
        private Long id;

        @Column(name = "receipt_number", nullable = false)
        private String receiptNumber;

        @ManyToOne
        @JoinColumn(name = "store_id", nullable = false)
        private Store store;

        @ManyToOne
        @JoinColumn(name = "terminal_id", nullable = false)
        private Terminal terminal;

        @ManyToOne
        @JoinColumn(name = "cashier_id", nullable = false)
        private Cashier cashier;

        @Column(name = "sale_time", nullable = false)
        private Instant saleTime;

        @Convert(converter = SaleStatusConverter.class)
        @Column(name = "status", nullable = false)
        private SaleStatus status;

        @Column(name = "total_amount", nullable = false)
        private BigDecimal totalAmount;

        @Column(name = "tax_amount", nullable = false)
        private BigDecimal taxAmount;

        @Column(name = "discount_amount", nullable = false)
        private BigDecimal discountAmount;

        @Column(name = "note")
        private String note;

        @Column(name = "item_count", nullable = false)
        private Long itemCount;

        @OneToMany(mappedBy = "sale")
        private List<SaleLine> lines = new ArrayList<>();

        public Sale() {}

        public Sale(Long id, String receiptNumber, Store store, Terminal terminal, Cashier cashier,
                Instant saleTime, SaleStatus status, BigDecimal totalAmount, BigDecimal taxAmount,
                BigDecimal discountAmount, String note, Long itemCount) {
            this.id = id;
            this.receiptNumber = receiptNumber;
            this.store = store;
            this.terminal = terminal;
            this.cashier = cashier;
            this.saleTime = saleTime;
            this.status = status;
            this.totalAmount = totalAmount;
            this.taxAmount = taxAmount;
            this.discountAmount = discountAmount;
            this.note = note;
            this.itemCount = itemCount;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getReceiptNumber() { return receiptNumber; }
        public void setReceiptNumber(String receiptNumber) { this.receiptNumber = receiptNumber; }
        public Store getStore() { return store; }
        public void setStore(Store store) { this.store = store; }
        public Terminal getTerminal() { return terminal; }
        public void setTerminal(Terminal terminal) { this.terminal = terminal; }
        public Cashier getCashier() { return cashier; }
        public void setCashier(Cashier cashier) { this.cashier = cashier; }
        public Instant getSaleTime() { return saleTime; }
        public void setSaleTime(Instant saleTime) { this.saleTime = saleTime; }
        public SaleStatus getStatus() { return status; }
        public void setStatus(SaleStatus status) { this.status = status; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
        public BigDecimal getTaxAmount() { return taxAmount; }
        public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
        public BigDecimal getDiscountAmount() { return discountAmount; }
        public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public Long getItemCount() { return itemCount; }
        public void setItemCount(Long itemCount) { this.itemCount = itemCount; }
        public List<SaleLine> getLines() { return lines; }
        public void setLines(List<SaleLine> lines) { this.lines = lines; }
    }

    @Entity(name = "SaleLine")
    @Table(name = "sale_line")
    public static class SaleLine {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "sale_id", nullable = false)
        private Sale sale;

        @Column(name = "line_number", nullable = false)
        private int lineNumber;

        @Column(name = "product_sku", nullable = false)
        private String productSku;

        @Column(name = "quantity", nullable = false)
        private BigDecimal quantity;

        @Column(name = "unit_price", nullable = false)
        private BigDecimal unitPrice;

        @Column(name = "line_total", nullable = false)
        private BigDecimal lineTotal;

        public SaleLine() {}

        public SaleLine(Long id, Sale sale, int lineNumber, String productSku,
                BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
            this.id = id;
            this.sale = sale;
            this.lineNumber = lineNumber;
            this.productSku = productSku;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.lineTotal = lineTotal;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Sale getSale() { return sale; }
        public void setSale(Sale sale) { this.sale = sale; }
        public int getLineNumber() { return lineNumber; }
        public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
        public String getProductSku() { return productSku; }
        public void setProductSku(String productSku) { this.productSku = productSku; }
        public BigDecimal getQuantity() { return quantity; }
        public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public BigDecimal getLineTotal() { return lineTotal; }
        public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
    }
}
