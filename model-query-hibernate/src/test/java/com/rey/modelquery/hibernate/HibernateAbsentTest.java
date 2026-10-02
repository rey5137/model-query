package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/** The Hibernate provider support is registered with {@code ServiceLoader}, and must stay inert without Hibernate. */
class HibernateAbsentTest {

    /** Loads this module's classes itself and refuses {@code org.hibernate}, as a class path without Hibernate does. */
    private static final class WithoutHibernate extends ClassLoader {

        WithoutHibernate() {
            super(HibernateAbsentTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.hibernate.")) {
                throw new ClassNotFoundException(name);
            }
            if (name.startsWith("com.rey.modelquery.hibernate.") && !name.contains("HibernateAbsentTest")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = in.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static EntityManagerFactory plainFactory() {
        return (EntityManagerFactory) Proxy.newProxyInstance(HibernateAbsentTest.class.getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "unwrap" -> throw new PersistenceException("not a provider factory");
                    case "getProperties" -> Map.of();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResolvedVendor resolveUnder(ClassLoader loader) {
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return VendorResolver.resolve(plainFactory(), Optional.empty(), MysqlStreamingMode.ROW_BY_ROW);
        } finally {
            thread.setContextClassLoader(before);
        }
    }

    @Test
    void r_vnd_04_the_hibernate_provider_support_is_not_picked_for_a_factory_that_is_not_hibernate() {
        ResolvedVendor resolved = resolveUnder(HibernateAbsentTest.class.getClassLoader());

        assertThat(resolved.providerSupport()).isEmpty();
        assertThat(resolved.detectedVendor()).isEqualTo(DatabaseVendor.OTHER);
    }

    @Test
    void r_vnd_04_the_hibernate_provider_support_stays_inert_when_hibernate_is_absent() {
        WithoutHibernate loader = new WithoutHibernate();
        // The support is still registered, but asking it about a factory fails to link: the resolver must skip it.
        ProviderSupport registered = ServiceLoader.load(ProviderSupport.class, loader).iterator().next();
        assertThatThrownBy(() -> registered.supports(plainFactory())).isInstanceOf(LinkageError.class);

        ResolvedVendor resolved = resolveUnder(loader);

        assertThat(resolved.providerSupport()).isEmpty();
        assertThat(resolved.detectedVendor()).isEqualTo(DatabaseVendor.OTHER);
    }
}
