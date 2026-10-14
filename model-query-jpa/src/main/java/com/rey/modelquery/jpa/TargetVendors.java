package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.vendor.ResolvedVendor;
import jakarta.persistence.EntityManagerFactory;
import java.util.Set;

/**
 * The check of a factory's vendor against the declared target vendors, shared by {@code ModelQueryExecutor.create}
 * and {@link ModelQueryConfig#checkTargets(EntityManagerFactory)}.
 *
 * @implSpec R-VND-19
 */
final class TargetVendors {

    private TargetVendors() {}

    /**
     * Throws {@code MQ4012} when {@code resolved}'s configured or detected vendor, not its profile's, is not one of
     * {@code targets}.
     */
    static void check(Set<DatabaseVendor> targets, EntityManagerFactory emf, ResolvedVendor resolved) {
        DatabaseVendor vendor = resolved.detectedVendor();
        if (targets.contains(vendor)) {
            return;
        }
        throw new ModelQueryConfigurationException(MqCode.MQ4012, "EntityManagerFactory " + nameOf(emf, resolved)
                + " is on " + vendor + ", " + howFound(resolved.source()) + ", which is not one of the target vendors "
                + targets + "; connect it to one of them, or add " + vendor + " to ModelQueryConfig.targetVendors(...) "
                + "or modelquery.target-vendors");
    }

    private static String howFound(ResolvedVendor.Source source) {
        return switch (source) {
            case CONFIG -> "set by ModelQueryConfig.vendor(...) or modelquery.vendor";
            case PROVIDER -> "detected by the persistence provider";
            case METADATA -> "detected from DatabaseMetaData";
            case NONE -> "as no vendor could be detected";
        };
    }

    /**
     * The factory's name, quoted, as its provider support answers {@link
     * com.rey.modelquery.jpa.spi.ProviderSupport#factoryName(EntityManagerFactory)}; else its class and identity, since
     * JPA 3.1 gives a factory no name and its properties no portable key for one.
     */
    private static String nameOf(EntityManagerFactory emf, ResolvedVendor resolved) {
        return resolved.providerSupport().flatMap(support -> support.factoryName(emf)).map(name -> "'" + name + "'")
                .orElseGet(() -> emf.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(emf)));
    }
}
