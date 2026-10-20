package com.rey.modelquery.tck.inheritance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;

/** Sibling reference attributes and assigned ids for treated paging (AC-PAG-29, AC-PAG-30). */
public final class PagingEntities {

    private PagingEntities() {}

    @Entity(name = "PagingStPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class StPayment {
        @Id public Long id;
    }

    @Entity(name = "PagingStCard")
    public static class StCard extends StPayment {
        @Column(name = "card_reference")
        public String reference;
    }

    @Entity(name = "PagingStBank")
    public static class StBank extends StPayment {
        @Column(name = "bank_reference")
        public String reference;
    }

    @Entity(name = "PagingJdPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    public static class JdPayment {
        @Id public Long id;
    }

    @Entity(name = "PagingJdCard")
    public static class JdCard extends JdPayment {
        public String reference;
    }

    @Entity(name = "PagingJdBank")
    public static class JdBank extends JdPayment {
        public String reference;
    }

    @Entity(name = "PagingTpPayment")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    public static class TpPayment {
        @Id public Long id;
    }

    @Entity(name = "PagingTpCard")
    public static class TpCard extends TpPayment {
        public String reference;
    }

    @Entity(name = "PagingTpBank")
    public static class TpBank extends TpPayment {
        public String reference;
    }
}
