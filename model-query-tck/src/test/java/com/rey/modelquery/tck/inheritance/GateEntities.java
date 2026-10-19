package com.rey.modelquery.tck.inheritance;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.SQLRestriction;

/**
 * The mapping gates of AC-VND-18, each a payment with a card subtype: an {@code @IdClass} and an {@code @EmbeddedId}
 * hierarchy, a {@code null} and a {@code not null} discriminator value, a subtype {@code @SQLRestriction} (7.4 refuses
 * it at bootstrap, so a test maps it only where it runs), and a root {@code @SQLRestriction} on each strategy that
 * hides the {@code hidden} rows.
 */
public final class GateEntities {

    private GateEntities() {}

    public static final class Key implements Serializable {
        public Long id;
        public String region;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(id, key.id) && Objects.equals(region, key.region);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, region);
        }
    }

    @Entity(name = "GateIdClassPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @IdClass(Key.class)
    public static class IdClassPayment {
        @Id public Long id;
        @Id public String region;
        public String status;
    }

    @Entity(name = "GateIdClassCard")
    public static class IdClassCard extends IdClassPayment {
        public String number;
    }

    @Embeddable
    public static class EmbeddedKey implements Serializable {
        public Long id;
        public String region;

        @Override
        public boolean equals(Object other) {
            return other instanceof EmbeddedKey key && Objects.equals(id, key.id) && Objects.equals(region, key.region);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, region);
        }
    }

    @Entity(name = "GateEmbeddedPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class EmbeddedPayment {
        @EmbeddedId public EmbeddedKey key;
        public String status;
    }

    @Entity(name = "GateEmbeddedCard")
    public static class EmbeddedCard extends EmbeddedPayment {
        public String number;
    }

    @Entity(name = "GateNullPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class NullPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "GateNullCard")
    @DiscriminatorValue("null")
    public static class NullCard extends NullPayment {
        public String number;
    }

    @Entity(name = "GateNotNullPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class NotNullPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "GateNotNullCard")
    @DiscriminatorValue("not null")
    public static class NotNullCard extends NotNullPayment {
        public String number;
    }

    @Entity(name = "GateSubtypeRestrictedPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class SubtypeRestrictedPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "GateSubtypeRestrictedCard")
    @SQLRestriction("status <> 'hidden'")
    public static class SubtypeRestrictedCard extends SubtypeRestrictedPayment {
        public String number;
    }

    @Entity(name = "GateRootStPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @SQLRestriction("status <> 'hidden'")
    public static class RootStPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "GateRootStCard")
    public static class RootStCard extends RootStPayment {
        public String number;
    }

    @Entity(name = "GateRootJdPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    @SQLRestriction("status <> 'hidden'")
    public static class RootJdPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "GateRootJdCard")
    public static class RootJdCard extends RootJdPayment {
        public String number;
    }

    @Entity(name = "GateRootTpPayment")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @SQLRestriction("status <> 'hidden'")
    public static class RootTpPayment {
        @Id public Long id;
        public String status;
    }

    @Entity(name = "GateRootTpCard")
    public static class RootTpCard extends RootTpPayment {
        public String number;
    }
}
