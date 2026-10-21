package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.Set;

/**
 * Which {@link OperatorFeature}s the target's vendor has and the persistence provider can render, the two facts an
 * operator that {@link FilterOperator#requires requires} a feature is checked against when its filter renders, before
 * any statement: the vendor's first ({@code MQ4015}), then the provider's ({@code MQ4014}). The executor asks both
 * for every feature once, when it is built, so {@code core} names no vendor or provider (INV-6, INV-7). Immutable.
 *
 * @implSpec vendor/40 R-VND-23, api/12 R-FLT-20, D-169
 */
@EngineFacing
@Incubating
public final class OperatorGate {

    private final String vendor;
    private final Set<OperatorFeature> vendorFeatures;
    private final Set<OperatorFeature> providerFeatures;

    private OperatorGate(String vendor, Set<OperatorFeature> vendorFeatures, Set<OperatorFeature> providerFeatures) {
        this.vendor = vendor;
        this.vendorFeatures = vendorFeatures;
        this.providerFeatures = providerFeatures;
    }

    /**
     * A gate for {@code vendor}, named in messages, whose database has {@code vendorFeatures} and whose provider
     * renders {@code providerFeatures}; both sets are copied.
     */
    public static OperatorGate of(String vendor, Set<OperatorFeature> vendorFeatures,
            Set<OperatorFeature> providerFeatures) {
        return new OperatorGate(Objects.requireNonNull(vendor, "vendor"), copy(vendorFeatures, "vendorFeatures"),
                copy(providerFeatures, "providerFeatures"));
    }

    private static Set<OperatorFeature> copy(Set<OperatorFeature> features, String name) {
        Objects.requireNonNull(features, name);
        return Set.copyOf(features);
    }

    /** The vendor this gate answers for, as messages name it. */
    public String vendor() {
        return vendor;
    }

    /** Whether the target's vendor has {@code feature} ({@code VendorProfile#operators}); false is {@code MQ4015}. */
    public boolean vendorServes(OperatorFeature feature) {
        return vendorFeatures.contains(Objects.requireNonNull(feature, "feature"));
    }

    /** Whether the provider can render {@code feature} ({@code ProviderSupport#operators}); false is {@code MQ4014}. */
    public boolean providerServes(OperatorFeature feature) {
        return providerFeatures.contains(Objects.requireNonNull(feature, "feature"));
    }

    @Override
    public String toString() {
        return "OperatorGate[" + vendor + ", vendor " + vendorFeatures + ", provider " + providerFeatures + "]";
    }
}
