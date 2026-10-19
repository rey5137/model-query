package com.rey.modelquery.tck.inheritance;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SecondaryTable;
import java.util.List;
import org.hibernate.annotations.DiscriminatorFormula;
import org.hibernate.annotations.DiscriminatorOptions;
import org.hibernate.annotations.SecondaryRow;

/**
 * The measured mapping variants (RFC 0016 §10, AC-VND-18), each a payment with a card and a bank subtype, a premium
 * card below the card, and a holder reaching the payment and the card, as {@link CellEntities}' trees: a forced
 * discriminator, a formula discriminator, an optional and a required secondary table on the card, and a
 * {@code JOINED} hierarchy with an explicit discriminator column. The two {@code JOINED} discriminator settings are a
 * factory property over {@link CellEntities}' and this explicit hierarchy.
 */
public final class VariantEntities {

    private VariantEntities() {}

    @Entity(name = "VarForcePayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorOptions(force = true)
    public static class ForcePayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "VarForceCard")
    public static class ForceCard extends ForcePayment {
        public String number;
        @ManyToOne public ForceHolder owner;
        @OneToMany(mappedBy = "card") public List<ForceHolder> holders;
    }

    @Entity(name = "VarForceBank")
    public static class ForceBank extends ForcePayment {}

    @Entity(name = "VarForcePremium")
    public static class ForcePremium extends ForceCard {
        public String tier;
    }

    @Entity(name = "VarForceHolder")
    public static class ForceHolder {
        @Id public Long id;
        @ManyToOne public ForcePayment payment;
        @ManyToOne public ForceCard card;
    }

    /** The discriminator is read from {@code kind}, which each type's constructor writes. */
    @Entity(name = "VarFormulaPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorFormula("kind")
    @DiscriminatorValue("P")
    public static class FormulaPayment {
        @Id public Long id;
        public String status;
        @Column(name = "kind") public String kind = "P";
    }

    @Entity(name = "VarFormulaCard")
    @DiscriminatorValue("C")
    public static class FormulaCard extends FormulaPayment {
        public String number;
        @ManyToOne public FormulaHolder owner;
        @OneToMany(mappedBy = "card") public List<FormulaHolder> holders;

        public FormulaCard() {
            kind = "C";
        }
    }

    @Entity(name = "VarFormulaBank")
    @DiscriminatorValue("B")
    public static class FormulaBank extends FormulaPayment {
        public FormulaBank() {
            kind = "B";
        }
    }

    @Entity(name = "VarFormulaPremium")
    @DiscriminatorValue("R")
    public static class FormulaPremium extends FormulaCard {
        public String tier;

        public FormulaPremium() {
            kind = "R";
        }
    }

    @Entity(name = "VarFormulaHolder")
    public static class FormulaHolder {
        @Id public Long id;
        @ManyToOne public FormulaPayment payment;
        @ManyToOne public FormulaCard card;
    }

    @Entity(name = "VarOptionalPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class OptionalPayment {
        @Id public Long id;
        public String status;
    }

    /** Optional: Hibernate writes no secondary row while {@code number} is null. */
    @Entity(name = "VarOptionalCard")
    @SecondaryTable(name = "var_optional_card_extra")
    public static class OptionalCard extends OptionalPayment {
        @Column(table = "var_optional_card_extra") public String number;
        @ManyToOne public OptionalHolder owner;
        @OneToMany(mappedBy = "card") public List<OptionalHolder> holders;
    }

    @Entity(name = "VarOptionalBank")
    public static class OptionalBank extends OptionalPayment {}

    @Entity(name = "VarOptionalPremium")
    public static class OptionalPremium extends OptionalCard {
        public String tier;
    }

    @Entity(name = "VarOptionalHolder")
    public static class OptionalHolder {
        @Id public Long id;
        @ManyToOne public OptionalPayment payment;
        @ManyToOne public OptionalCard card;
    }

    @Entity(name = "VarRequiredPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class RequiredPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "VarRequiredCard")
    @SecondaryTable(name = "var_required_card_extra")
    @SecondaryRow(table = "var_required_card_extra", optional = false)
    public static class RequiredCard extends RequiredPayment {
        @Column(table = "var_required_card_extra") public String number;
        @ManyToOne public RequiredHolder owner;
        @OneToMany(mappedBy = "card") public List<RequiredHolder> holders;
    }

    @Entity(name = "VarRequiredBank")
    public static class RequiredBank extends RequiredPayment {}

    @Entity(name = "VarRequiredPremium")
    public static class RequiredPremium extends RequiredCard {
        public String tier;
    }

    @Entity(name = "VarRequiredHolder")
    public static class RequiredHolder {
        @Id public Long id;
        @ManyToOne public RequiredPayment payment;
        @ManyToOne public RequiredCard card;
    }

    @Entity(name = "VarJoinedPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind")
    public static class JoinedPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "VarJoinedCard")
    public static class JoinedCard extends JoinedPayment {
        public String number;
        @ManyToOne public JoinedHolder owner;
        @OneToMany(mappedBy = "card") public List<JoinedHolder> holders;
    }

    @Entity(name = "VarJoinedBank")
    public static class JoinedBank extends JoinedPayment {}

    @Entity(name = "VarJoinedPremium")
    public static class JoinedPremium extends JoinedCard {
        public String tier;
    }

    @Entity(name = "VarJoinedHolder")
    public static class JoinedHolder {
        @Id public Long id;
        @ManyToOne public JoinedPayment payment;
        @ManyToOne public JoinedCard card;
    }
}
