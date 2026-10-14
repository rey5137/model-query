package com.rey.modelquery.spring.boot;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ModelQueryConfig;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.AbstractEntityManagerFactoryBean;
import org.springframework.util.function.SingletonSupplier;

/**
 * Checks each eagerly created {@code EntityManagerFactory} bean against the {@code modelquery.target-vendors} property
 * once it is initialised, so a context on the wrong database fails startup rather than its first query (R-SPR-17,
 * D-133). Only the property is read: no {@code ModelQueryConfig} bean or {@link ModelQueryConfigurer} is resolved or
 * called, so target vendors declared in code are enforced by {@code ModelQueryExecutor.create} when a repository is
 * created. A lazy factory is skipped, as that check covers it. When one factory fails, the other eager factories are
 * created and checked too, so one {@code MQ4012} names them all; one whose creation fails for another reason is left
 * out and its exception added to the {@code MQ4012} as suppressed. A factory bean with a bootstrap executor
 * ({@code spring.data.jpa.repositories.bootstrap-mode} {@code deferred} or {@code lazy}) hands out a proxy at once,
 * and any call on it waits for the background build; so it is only recorded, and every recorded factory is checked
 * once the singletons are instantiated, with one {@code MQ4012} naming all of them. An application
 * {@code ModelQueryConfig} bean makes the property an {@code MQ4006} first, as that is the more actionable error.
 *
 * @implSpec R-SPR-17, R-VND-19
 */
final class TargetVendorCheck implements BeanPostProcessor, SmartInitializingSingleton {

    private final ConfigurableListableBeanFactory beanFactory;
    /** The config holding the property's target vendors and {@code modelquery.vendor}, or empty when the property is unset. */
    private final SingletonSupplier<Optional<ModelQueryConfig>> config;
    /** Each factory bean checked, by name: its failure, or empty when it is on a target vendor. */
    private final Map<String, String> checked = new ConcurrentHashMap<>();
    /** The bean names of factories built in the background, checked after the singletons are instantiated. */
    private final Set<String> deferred = ConcurrentHashMap.newKeySet();
    /** Set while the factories of one failure are being created, so each records its result without throwing. */
    private final ThreadLocal<Boolean> sweeping = ThreadLocal.withInitial(() -> Boolean.FALSE);

    TargetVendorCheck(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.config = SingletonSupplier.of(() -> {
            Binder binder = Binder.get(environment);
            return binder.bind(ModelQueryProperties.TARGET_VENDORS, Bindable.listOf(String.class)).map(names -> {
                ModelQueryConfig config = ModelQueryConfig.defaults().targetVendors(names.toArray(String[]::new));
                // As the starter's config applies modelquery.vendor, so MQ4012 reports an explicit vendor as such.
                return binder.bind("modelquery.vendor", String.class).map(config::vendor).orElse(config);
            }).map(Optional::of).orElse(Optional.empty());
        });
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof EntityManagerFactory factory) || skipped(beanName) || config.obtain().isEmpty()) {
            return bean;
        }
        refuseOwnConfig();
        if (buildsInBackground(beanName)) {
            deferred.add(beanName);
            return bean;
        }
        String failure = failureOf(config.obtain().get(), factory, beanName);
        checked.put(beanName, failure);
        if (failure.isEmpty() || sweeping.get()) {
            return bean;
        }
        throw sweep(new ArrayList<>(List.of(failure)));
    }

    /** Checks each factory recorded as building in the background; the eager ones have thrown by now. */
    @Override
    public void afterSingletonsInstantiated() {
        if (deferred.isEmpty()) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (String name : new TreeSet<>(deferred)) {
            String failure = failureOf(config.obtain().orElseThrow(), beanFactory.getBean(name,
                    EntityManagerFactory.class), name);
            if (!failure.isEmpty()) {
                failures.add(failure);
            }
        }
        deferred.clear();
        if (!failures.isEmpty()) {
            throw mq4012(failures);
        }
    }

    /** Whether {@code beanName} is a factory bean given a bootstrap executor, already created with its product. */
    private boolean buildsInBackground(String beanName) {
        return beanFactory.containsBean("&" + beanName)
                && beanFactory.isSingleton("&" + beanName)
                && beanFactory.getBean("&" + beanName) instanceof AbstractEntityManagerFactoryBean factoryBean
                && factoryBean.getBootstrapExecutor() != null;
    }

    /** Throws {@code MQ4006} when the application defines its own {@code ModelQueryConfig}, without creating it. */
    private void refuseOwnConfig() {
        for (String name : beanFactory.getBeanNamesForType(ModelQueryConfig.class, false, false)) {
            if (!ModelQueryAutoConfiguration.isOwn(beanFactory, name)) {
                throw ModelQueryAutoConfiguration.replacesStarterConfig(name, List.of("property "
                        + ModelQueryProperties.TARGET_VENDORS));
            }
        }
    }

    /** The {@code MQ4012} for {@code failures} and every other eager factory's, creating those not yet created. */
    private ModelQueryConfigurationException sweep(List<String> failures) {
        List<BeansException> uncreated = new ArrayList<>();
        sweeping.set(Boolean.TRUE);
        try {
            for (String other : beanFactory.getBeanNamesForType(EntityManagerFactory.class, true, false)) {
                if (!checked.containsKey(other) && !skipped(other) && !beanFactory.isCurrentlyInCreation(other)) {
                    try {
                        beanFactory.getBean(other, EntityManagerFactory.class);
                    } catch (BeansException e) {
                        // Its own failure must not hide the MQ4012 found; it is kept as a suppressed exception.
                        uncreated.add(e);
                    }
                    String otherFailure = checked.getOrDefault(other, "");
                    if (!otherFailure.isEmpty()) {
                        failures.add(otherFailure);
                    }
                }
            }
        } finally {
            sweeping.remove();
        }
        var mq4012 = mq4012(failures);
        uncreated.forEach(mq4012::addSuppressed);
        return mq4012;
    }

    private static ModelQueryConfigurationException mq4012(List<String> failures) {
        return new ModelQueryConfigurationException(MqCode.MQ4012, "EntityManagerFactory beans not on a target "
                + "vendor:" + failures.stream().map(line -> "\n- " + line).collect(Collectors.joining()));
    }

    /** Whether {@code beanName} is created lazily or per use, so the executor's own check is left to it. */
    private boolean skipped(String beanName) {
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return false;
        }
        BeanDefinition definition = beanFactory.getMergedBeanDefinition(beanName);
        return definition.isLazyInit() || !definition.isSingleton();
    }

    /** The factory's failure, naming its bean, or empty when it is on one of the config's target vendors. */
    private static String failureOf(ModelQueryConfig config, EntityManagerFactory factory, String beanName) {
        try {
            config.checkTargets(factory);
            return "";
        } catch (ModelQueryConfigurationException e) {
            if (e.code() != MqCode.MQ4012) {
                throw e;
            }
            // The message is the code, ": " and the detail; the detail is repeated under the bean's name.
            String prefix = MqCode.MQ4012.code() + ": ";
            String message = e.getMessage();
            return "bean '" + beanName + "': " + (message.startsWith(prefix) ? message.substring(prefix.length())
                    : message);
        }
    }
}
