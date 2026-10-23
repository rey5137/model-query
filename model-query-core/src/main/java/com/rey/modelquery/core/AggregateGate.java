package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.Set;

/**
 * Which {@link AggregateFeature}s the target's vendor has, the fact an aggregate that requires one is checked against
 * before any statement ({@code MQ4015}). The executor asks the profile for every feature once, when it is built, so
 * {@code core} names no vendor (INV-6, INV-7). Immutable.
 *
 * @implSpec vendor/40 R-VND-29, D-185
 */
@EngineFacing
@Incubating
public final class AggregateGate {

    private final String vendor;
    private final Set<AggregateFeature> vendorFeatures;

    private AggregateGate(String vendor, Set<AggregateFeature> vendorFeatures) {
        this.vendor = vendor;
        this.vendorFeatures = vendorFeatures;
    }

    /** A gate for {@code vendor}, named in messages, whose database has {@code vendorFeatures}; the set is copied. */
    public static AggregateGate of(String vendor, Set<AggregateFeature> vendorFeatures) {
        return new AggregateGate(Objects.requireNonNull(vendor, "vendor"),
                Set.copyOf(Objects.requireNonNull(vendorFeatures, "vendorFeatures")));
    }

    /** The vendor this gate answers for, as messages name it. */
    public String vendor() {
        return vendor;
    }

    /** Whether the target's vendor has {@code feature} ({@code VendorProfile#aggregates}); false is {@code MQ4015}. */
    public boolean vendorServes(AggregateFeature feature) {
        return vendorFeatures.contains(Objects.requireNonNull(feature, "feature"));
    }

    @Override
    public String toString() {
        return "AggregateGate[" + vendor + ", " + vendorFeatures + "]";
    }
}
