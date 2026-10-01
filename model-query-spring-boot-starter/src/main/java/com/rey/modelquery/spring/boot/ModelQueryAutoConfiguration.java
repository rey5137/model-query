package com.rey.modelquery.spring.boot;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.spring.data.ModelQueryConfigurer;
import com.rey.modelquery.spring.data.ModelQueryRepositoryFactoryBean;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Auto-configuration for Model Query: reads {@code modelquery.*} into the context's one {@link ModelQueryConfig}, hands
 * it the {@link VendorProfile} beans and the {@link ChunkTransactions} bean, registering one that commits each chunk
 * on the write's own {@code JpaTransactionManager} unless the application defines its own, and makes the default
 * Spring Data JPA repositories use {@link ModelQueryRepositoryFactoryBean}. It adds no behaviour of its own (INV-8).
 *
 * @implSpec R-SPR-02, R-SPR-08, R-SPR-09, R-SPR-11, R-SPR-13, R-VND-03
 */
@AutoConfiguration
@ConditionalOnClass({ JpaRepositoryFactoryBean.class, EntityManagerFactory.class })
@EnableConfigurationProperties(ModelQueryProperties.class)
public class ModelQueryAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(ModelQueryAutoConfiguration.class.getName());

    private static final ConfigurationPropertyName PREFIX = ConfigurationPropertyName.of("modelquery");

    /**
     * Swaps the repositories registered with the stock {@code JpaRepositoryFactoryBean} to
     * {@link ModelQueryRepositoryFactoryBean}, keeping Boot's own repository registrar; a repository with a factory
     * bean class of its own is left alone (R-SPR-02, D-50). Each swapped definition is copied and re-registered under
     * its name rather than changed in place, so a repository another post-processor type-checked first, which left
     * a merged definition and an early stock factory bean cached, is still built with the model-query factory bean
     * (D-83).
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor modelQueryRepositoryFactoryBeanSwap() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                String stock = JpaRepositoryFactoryBean.class.getName();
                for (String name : registry.getBeanDefinitionNames().clone()) {
                    BeanDefinition definition = registry.getBeanDefinition(name);
                    if (stock.equals(definition.getBeanClassName())) {
                        GenericBeanDefinition swapped = new GenericBeanDefinition(definition);
                        swapped.setBeanClassName(ModelQueryRepositoryFactoryBean.class.getName());
                        registry.removeBeanDefinition(name);
                        registry.registerBeanDefinition(name, swapped);
                    }
                }
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                // Nothing to do: only the registry is changed.
            }
        };
    }

    /**
     * Runs each chunk of a {@code commitEachChunk()} write in a new transaction of the {@code JpaTransactionManager}
     * bound to the write's {@code EntityManagerFactory}, unless the application defines a {@link ChunkTransactions}
     * bean of its own (R-SPR-11).
     */
    @Bean
    @ConditionalOnMissingBean
    ChunkTransactions modelQueryChunkTransactions(ObjectProvider<PlatformTransactionManager> transactionManagers) {
        return new SpringChunkTransactions(transactionManagers);
    }

    /**
     * The one config every repository's executor starts from, unless the application defines its own (D-54).
     *
     * @throws ModelQueryConfigurationException {@code MQ4001} for an unknown {@code modelquery.vendor}, {@code MQ4003}
     *     for an out-of-range size or timeout, {@code MQ4005} when the vendor is set with several
     *     {@code EntityManagerFactory} beans and no {@link ModelQueryConfigurer}
     */
    @Bean
    @ConditionalOnMissingBean
    ModelQueryConfig modelQueryConfig(ModelQueryProperties properties, ObjectProvider<VendorProfile> profiles,
            ObjectProvider<ModelQueryConfigurer> configurer, ObjectProvider<ChunkTransactions> chunkTransactions,
            ConfigurableListableBeanFactory beanFactory) {
        ModelQueryConfig config = ModelQueryConfig.defaults().vendorProfiles(profiles.orderedStream().toList());
        config = ifSet(config, chunkTransactions.getIfAvailable(), ModelQueryConfig::chunkTransactions);
        if (properties.getVendor() != null) {
            if (beanFactory.getBeanNamesForType(EntityManagerFactory.class).length > 1
                    && configurer.getIfAvailable() == null) {
                throw new ModelQueryConfigurationException(MqCode.MQ4005, "modelquery.vendor=" + properties.getVendor()
                        + " would force one vendor on every EntityManagerFactory; define a ModelQueryConfigurer bean"
                        + " to choose the vendor per factory");
            }
            config = config.vendor(properties.getVendor());
        }
        config = ifSet(config, properties.getQueryTimeout(), ModelQueryConfig::queryTimeout);
        config = ifSet(config, properties.getExport().getPageSize(), ModelQueryConfig::exportPageSize);
        config = ifSet(config, properties.getPrimaryKeyFirst().getBatchSize(),
                ModelQueryConfig::primaryKeyFirstBatchSize);
        config = ifSet(config, properties.getStream().getFetchSize(), ModelQueryConfig::streamFetchSize);
        config = ifSet(config, properties.getMysql().getStreamingMode(), ModelQueryConfig::mysqlStreamingMode);
        config = ifSet(config, properties.getBulkWrite().getPersistenceContext(),
                ModelQueryConfig::persistenceContextMode);
        config = ifSet(config, properties.getBulkWrite().getChunkSize(), ModelQueryConfig::bulkWriteChunkSize);
        if (properties.getKeyset().getNullKeys() != null) {
            config = config.keysetNullKeys(properties.getKeyset().getNullKeys());
            if (properties.getKeyset().getNullKeys() == KeysetNullKeys.HONOUR_NULL_PRECEDENCE) {
                LOG.log(Level.WARNING, "modelquery.keyset.null-keys=honour-null-precedence is a migration aid: a keyset"
                        + " page over a nullable column with DEFAULT null precedence pages its NULLs where the"
                        + " database sorts them, where the default 'fail' throws MQ2202 (R-PAG-05, R-SPR-09)");
            }
        }
        return config;
    }

    /** {@code config} with {@code value} applied by {@code setter}, or unchanged when the property is unset. */
    private static <V> ModelQueryConfig ifSet(ModelQueryConfig config, V value,
            BiFunction<ModelQueryConfig, V, ModelQueryConfig> setter) {
        return value == null ? config : setter.apply(config, value);
    }

    /**
     * Refuses a {@link ModelQueryConfig} bean of the application's own that would silently drop what the starter
     * reads: a {@link VendorProfile} bean the config does not hold, a {@link ChunkTransactions} bean of the
     * application's own it does not hold, or a {@code modelquery.*} property, which only the starter's own config
     * reads (R-SPR-11, R-SPR-13, D-54).
     *
     * @throws ModelQueryConfigurationException {@code MQ4006} on startup, naming what the config drops
     */
    @Bean
    SmartInitializingSingleton modelQueryConfigCheck(ObjectProvider<VendorProfile> profiles,
            ObjectProvider<ChunkTransactions> chunkTransactions, Environment environment,
            ConfigurableListableBeanFactory beanFactory) {
        return () -> {
            for (String name : beanFactory.getBeanNamesForType(ModelQueryConfig.class, false, false)) {
                if (!isOwn(beanFactory, name)) {
                    checkOwnConfig(name, beanFactory.getBean(name, ModelQueryConfig.class), profiles,
                            chunkTransactions, environment);
                }
            }
        };
    }

    /** Whether {@code name} is the config bean {@link #modelQueryConfig} declares. */
    private static boolean isOwn(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!beanFactory.containsBeanDefinition(name)
                || !(beanFactory.getBeanDefinition(name) instanceof AnnotatedBeanDefinition annotated)) {
            return false;
        }
        MethodMetadata method = annotated.getFactoryMethodMetadata();
        return method != null && method.getDeclaringClassName().equals(ModelQueryAutoConfiguration.class.getName());
    }

    private static void checkOwnConfig(String name, ModelQueryConfig config, ObjectProvider<VendorProfile> profiles,
            ObjectProvider<ChunkTransactions> chunkTransactions, Environment environment) {
        List<String> dropped = new ArrayList<>();
        profiles.orderedStream()
                .filter(profile -> config.vendorProfiles().stream().noneMatch(held -> held == profile))
                .forEach(profile -> dropped.add("VendorProfile bean " + profile.getClass().getName()));
        // The starter's own callback is a default, not something the application asked for, so dropping it is not
        // refused: such a config's commitEachChunk() writes throw MQ4004 instead.
        chunkTransactions.orderedStream()
                .filter(callback -> !(callback instanceof SpringChunkTransactions))
                .filter(callback -> config.chunkTransactions().orElse(null) != callback)
                .forEach(callback -> dropped.add("ChunkTransactions bean " + callback.getClass().getName()));
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                iterable.filter(PREFIX::isAncestorOf).stream()
                        .map(property -> "property " + property)
                        .filter(property -> !dropped.contains(property))
                        .forEach(dropped::add);
            }
        }
        if (!dropped.isEmpty()) {
            throw new ModelQueryConfigurationException(MqCode.MQ4006, "the application's ModelQueryConfig bean '"
                    + name + "' replaces the starter's and drops " + dropped + "; remove it and adjust the starter's "
                    + "config with a ModelQueryConfigurer bean instead");
        }
    }
}
