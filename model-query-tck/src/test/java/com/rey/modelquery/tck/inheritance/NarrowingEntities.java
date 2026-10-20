package com.rey.modelquery.tck.inheritance;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;

/** Nested-target fixtures for AC-VND-21, outside the packages the Spring contexts scan. */
public final class NarrowingEntities {

    private NarrowingEntities() {}

    @Entity(name = "NarrowingPayment")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    public static class NestedPayment {
        @Id public Long id;
        @ManyToOne public Currency currency;
    }

    @Entity(name = "NarrowingCard")
    public static class NestedCard extends NestedPayment {
        public String number;
    }

    @Entity(name = "NarrowingPremium")
    public static class NestedPremium extends NestedCard {
        public String tier;
    }

    @Entity(name = "NarrowingBank")
    public static class NestedBank extends NestedPayment {
        public String iban;
    }

    @Entity(name = "NarrowingHolder")
    public static class NestedHolder {
        @Id public Long id;
        @ManyToOne public NestedPayment payment;
    }

    @Entity(name = "NarrowingCurrency")
    public static class Currency {
        @Id public Long id;
        public String code;
    }
}
