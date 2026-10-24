package com.rey.modelquery.spike.rfc0022;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.stream.Stream;

/** RFC 0022 B: deliberately unenforced legacy references, alongside the RFC 0021 closure tables. */
public final class Rfc0022Entities {
    private Rfc0022Entities() {}

    public static final Class<?>[] ALL = Stream.concat(Stream.of(Rfc0022ClosureEntities.ALL), Stream.of(
            Invoice.class, Employee.class, Team.class, Region.class, EmployeeIc.class, EmployeeEmb.class,
            Attachment.class, Ticket.class)).toArray(Class<?>[]::new);

    @Entity(name = "Invoice")
    @Table(name = "invoice")
    public static class Invoice {
        @Id public Long id;
        @Column(name = "invoice_number") public String number;
        @Column(name = "rep_code") public String repCode;
        @Column(name = "region_code") public String regionCode;
        @Column(name = "issued_on") public LocalDate issuedOn;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "region_code", insertable = false, updatable = false,
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        public Region region;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "rep_code", insertable = false, updatable = false,
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        public Employee rep;
    }

    @Entity(name = "Employee")
    @Table(name = "employee")
    public static class Employee {
        @Id public String code;
        public String name;
        public boolean active;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "team_id") public Team team;
    }

    @Entity
    @Table(name = "team")
    public static class Team {
        @Id public Long id;
        public String name;
    }

    @Entity
    @Table(name = "region")
    public static class Region {
        @Id public String code;
        public String name;
        @Column(name = "rep_code") public String repCode;
    }

    public static class EmployeeKey implements Serializable {
        public String regionCode;
        public String code;
        public EmployeeKey() {}
        @Override public boolean equals(Object other) {
            return other instanceof EmployeeKey key
                    && Objects.equals(regionCode, key.regionCode) && Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return Objects.hash(regionCode, code); }
    }

    @Entity
    @IdClass(EmployeeKey.class)
    @Table(name = "employee_ic")
    public static class EmployeeIc {
        @Id @Column(name = "region_code") public String regionCode;
        @Id public String code;
        public String name;
        public boolean active;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "team_id") public Team team;
    }

    @Embeddable
    public static class EmployeeEmbeddedKey implements Serializable {
        @Column(name = "region_code") public String regionCode;
        public String code;
        public EmployeeEmbeddedKey() {}
        @Override public boolean equals(Object other) {
            return other instanceof EmployeeEmbeddedKey key
                    && Objects.equals(regionCode, key.regionCode) && Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return Objects.hash(regionCode, code); }
    }

    @Entity
    @Table(name = "employee_emb")
    public static class EmployeeEmb {
        @EmbeddedId public EmployeeEmbeddedKey id;
        public String name;
        public boolean active;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "team_id") public Team team;
    }

    @Entity
    @Table(name = "ticket")
    public static class Ticket {
        @Id public Long id;
        public String title;
    }

    @Entity
    @Table(name = "attachment")
    public static class Attachment {
        @Id public Long id;
        @Column(name = "owner_type") public String ownerType;
        @Column(name = "owner_id") public Long ownerId;
    }
}
