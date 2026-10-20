package com.rey.modelquery.tck.inheritance;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.List;

/**
 * One payment hierarchy per inheritance strategy, for the treated-statement contracts (AC-COL-52). Outside the
 * packages the Spring contexts scan: a {@code JOINED} or {@code TABLE_PER_CLASS} hierarchy makes Hibernate create
 * temporary tables at bootstrap, which would show in those tests' captured SQL.
 */
public final class AnchorEntities {

    private AnchorEntities() {}

    @Entity(name = "AnchorStPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class StPayment {
        @Id public Long id;
        public String status;
        @ManyToOne public StCustomer customer;
        @OneToMany(mappedBy = "payment") public List<StItem> items;
    }

    @Entity(name = "AnchorStCustomer")
    public static class StCustomer {
        @Id public Long id;
        public String name;
    }

    @Entity(name = "AnchorStItem")
    public static class StItem {
        @Id public Long id;
        public String label;
        @ManyToOne public StPayment payment;
    }

    /** Keyed by a {@code String} id, for a combination whose branches' root ids differ in type (R-SRC-20). */
    @Entity(name = "AnchorStVoucher")
    public static class StVoucher {
        @Id public String id;
        public String status;
        public String code;
    }

    @Entity(name = "AnchorStCard")
    public static class StCard extends StPayment {
        public String number;
    }

    @Entity(name = "AnchorStPremium")
    public static class StPremium extends StCard {}

    @Entity(name = "AnchorStBank")
    public static class StBank extends StPayment {}

    @Entity(name = "AnchorJdPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    public static class JdPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "AnchorJdCard")
    public static class JdCard extends JdPayment {
        public String number;
    }

    @Entity(name = "AnchorJdPremium")
    public static class JdPremium extends JdCard {}

    @Entity(name = "AnchorJdBank")
    public static class JdBank extends JdPayment {}

    @Entity(name = "AnchorTpPayment")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    public static class TpPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "AnchorTpCard")
    public static class TpCard extends TpPayment {
        public String number;
    }

    @Entity(name = "AnchorTpPremium")
    public static class TpPremium extends TpCard {}

    @Entity(name = "AnchorTpBank")
    public static class TpBank extends TpPayment {}
}
