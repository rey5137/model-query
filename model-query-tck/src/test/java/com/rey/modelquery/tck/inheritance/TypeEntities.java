package com.rey.modelquery.tck.inheritance;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;

/**
 * One payment hierarchy per inheritance strategy for the type reads (AC-COL-49, AC-FLT-30, AC-FLT-31): an abstract
 * card entity with a debit and a gift card, the gift mapped only in {@code META-INF/type-orm.xml}, a bank, and a
 * stray subclass no mapping names.
 */
public final class TypeEntities {

    private TypeEntities() {}

    @Entity(name = "TypeStPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class StPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "TypeStCard")
    public abstract static class StCard extends StPayment {}

    @Entity(name = "TypeStDebit")
    public static class StDebit extends StCard {}

    /** Mapped in {@code META-INF/type-orm.xml}. */
    public static class StGift extends StCard {}

    @Entity(name = "TypeStBank")
    public static class StBank extends StPayment {}

    public static class StStray extends StPayment {}

    /** Mapped only by the factory that writes its row: a reader without it meets an unmapped discriminator value. */
    @Entity(name = "TypeStGhost")
    public static class StGhost extends StPayment {}

    @Entity(name = "TypeJdPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    public static class JdPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "TypeJdCard")
    public abstract static class JdCard extends JdPayment {}

    @Entity(name = "TypeJdDebit")
    public static class JdDebit extends JdCard {}

    /** Mapped in {@code META-INF/type-orm.xml}. */
    public static class JdGift extends JdCard {}

    @Entity(name = "TypeJdBank")
    public static class JdBank extends JdPayment {}

    public static class JdStray extends JdPayment {}

    @Entity(name = "TypeTpPayment")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    public static class TpPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "TypeTpCard")
    public abstract static class TpCard extends TpPayment {}

    @Entity(name = "TypeTpDebit")
    public static class TpDebit extends TpCard {}

    /** Mapped in {@code META-INF/type-orm.xml}. */
    public static class TpGift extends TpCard {}

    @Entity(name = "TypeTpBank")
    public static class TpBank extends TpPayment {}

    public static class TpStray extends TpPayment {}
}
