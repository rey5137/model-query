package com.rey.modelquery.tck.inheritance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.List;

/**
 * The treated-cell hierarchies, one per strategy: a base payment, two sibling subtypes, a grandchild under the card,
 * and a holder whose to-one reaches the root (AC-VND-18, AC-VND-19). The card reaches holders through a to-one
 * ({@code owner}) and a to-many ({@code holders}), for a join below a treat and a path {@code exists} under it. On
 * {@code SINGLE_TABLE} the siblings also map one physical column under the same name ({@code code}) and under
 * different names ({@code alpha}, {@code beta}), for the shared-column cells (AC-COL-51). Outside the packages the
 * Spring contexts scan, like {@link AnchorEntities}.
 */
public final class CellEntities {

    private CellEntities() {}

    @Entity(name = "CellStPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class StPayment {
        @Id public Long id;
        public String status;
        public Integer amount;
    }

    @Entity(name = "CellStCard")
    public static class StCard extends StPayment {
        public String number;
        @ManyToOne public StHolder owner;
        @OneToMany(mappedBy = "card") public List<StHolder> holders;
        @Column(name = "code") public String code;
        @Column(name = "ab") public String alpha;
    }

    @Entity(name = "CellStBank")
    public static class StBank extends StPayment {
        public String iban;
        @Column(name = "code") public String code;
        @Column(name = "ab") public String beta;
    }

    @Entity(name = "CellStPremium")
    public static class StPremium extends StCard {
        public String tier;
    }

    @Entity(name = "CellStHolder")
    public static class StHolder {
        @Id public Long id;
        @ManyToOne public StPayment payment;
        @ManyToOne public StCard card;
    }

    @Entity(name = "CellJdPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    public static class JdPayment {
        @Id public Long id;
        public String status;
        public Integer amount;
    }

    @Entity(name = "CellJdCard")
    public static class JdCard extends JdPayment {
        public String number;
        @ManyToOne public JdHolder owner;
        @OneToMany(mappedBy = "card") public List<JdHolder> holders;
    }

    @Entity(name = "CellJdBank")
    public static class JdBank extends JdPayment {
        public String iban;
    }

    @Entity(name = "CellJdPremium")
    public static class JdPremium extends JdCard {
        public String tier;
    }

    @Entity(name = "CellJdHolder")
    public static class JdHolder {
        @Id public Long id;
        @ManyToOne public JdPayment payment;
        @ManyToOne public JdCard card;
    }

    @Entity(name = "CellTpPayment")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    public static class TpPayment {
        @Id public Long id;
        public String status;
        public Integer amount;
    }

    @Entity(name = "CellTpCard")
    public static class TpCard extends TpPayment {
        public String number;
        @ManyToOne public TpHolder owner;
        @OneToMany(mappedBy = "card") public List<TpHolder> holders;
    }

    @Entity(name = "CellTpBank")
    public static class TpBank extends TpPayment {
        public String iban;
    }

    @Entity(name = "CellTpPremium")
    public static class TpPremium extends TpCard {
        public String tier;
    }

    @Entity(name = "CellTpHolder")
    public static class TpHolder {
        @Id public Long id;
        @ManyToOne public TpPayment payment;
        @ManyToOne public TpCard card;
    }
}
