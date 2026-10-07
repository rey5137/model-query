package com.rey.modelquery.spring.boot;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.WriteAssignment;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.spring.data.ModelQueryConfigurer;
import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.spring.data.ModelQueryRepositoryFactoryBean;
import com.rey.modelquery.spring.data.ModelQueryRepositoryFragmentFactoryBean;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

/**
 * Auto-configuration for Model Query: reads {@code modelquery.*} into the context's one {@link ModelQueryConfig}, hands
 * it the {@link VendorProfile} beans, the {@link WriteAssignment} beans and the {@link ChunkTransactions} bean,
 * registering one that commits each chunk on the write's own {@code JpaTransactionManager} unless the application
 * defines its own, and makes the default Spring Data JPA repositories use {@link ModelQueryRepositoryFactoryBean}. It
 * adds no behaviour of its own (INV-8).
 *
 * @implSpec R-SPR-02, R-SPR-08, R-SPR-09, R-SPR-11, R-SPR-13, R-VND-03, R-WRT-49
 */
@Incubating
@AutoConfiguration
@ConditionalOnClass({ JpaRepositoryFactoryBean.class, EntityManagerFactory.class })
@EnableConfigurationProperties(ModelQueryProperties.class)
public class ModelQueryAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(ModelQueryAutoConfiguration.class.getName());

    private static final ConfigurationPropertyName PREFIX = ConfigurationPropertyName.of("modelquery");

    /**
     * Swaps the repositories registered with the stock {@code JpaRepositoryFactoryBean} to
     * {@link ModelQueryRepositoryFactoryBean}, keeping Boot's own repository registrar (R-SPR-02, D-50). An exact
     * {@code JpaRepositoryFactoryBean} is swapped; a {@link ModelQueryRepositoryFactoryBean} subclass is left alone;
     * any other {@code JpaRepositoryFactoryBean} subclass whose repository extends {@link ModelQueryRepository} keeps
     * its class and gets the fragment through {@code customImplementation}, re-registered as below (D-113). Each
     * swapped definition is copied and re-registered under its name rather than changed in place, so a repository
     * another post-processor type-checked first, which left a merged definition and an early stock factory bean
     * cached, is still built with the model-query factory bean (D-83). A {@code RootBeanDefinition} is cloned and
     * keeps its target type, now over {@code ModelQueryRepositoryFactoryBean} with the old generics. A swapped
     * definition moves to the end of the registration order, which changes the singleton creation order and the order
     * of an injected {@code List<Repository>}.
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor modelQueryRepositoryFactoryBeanSwap() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                String stock = JpaRepositoryFactoryBean.class.getName();
                ClassLoader classLoader = registry instanceof ConfigurableListableBeanFactory beanFactory
                        ? beanFactory.getBeanClassLoader() : ClassUtils.getDefaultClassLoader();
                for (String name : registry.getBeanDefinitionNames().clone()) {
                    BeanDefinition definition = registry.getBeanDefinition(name);
                    String beanClassName = definition.getBeanClassName();
                    if (beanClassName == null) {
                        continue;
                    }
                    if (stock.equals(beanClassName)) {
                        AbstractBeanDefinition swapped = copy(definition);
                        registry.removeBeanDefinition(name);
                        registry.registerBeanDefinition(name, swapped);
                        continue;
                    }
                    // Only a repository definition can be a factory bean candidate: the registrar registers each as a
                    // RootBeanDefinition carrying the target type, so no other bean's class is loaded here.
                    if (!(definition instanceof RootBeanDefinition root) || root.getTargetType() == null) {
                        continue;
                    }
                    Class<?> factoryBeanClass = load(beanClassName, classLoader);
                    if (factoryBeanClass == null
                            || !JpaRepositoryFactoryBean.class.isAssignableFrom(factoryBeanClass)) {
                        continue;
                    }
                    if (!ModelQueryRepositoryFactoryBean.class.isAssignableFrom(factoryBeanClass)) {
                        addFragment(registry, name, root, classLoader);
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
     * A {@code JpaRepositoryFactoryBean} subclass of the application's own whose repository extends
     * {@link ModelQueryRepository}: the starter keeps its class and re-registers a copy carrying a
     * {@link ModelQueryRepositoryFragmentFactoryBean} as the definition's {@code customImplementation} (R-SPR-02,
     * D-113). A definition that already sets {@code customImplementation} is refused with {@code MQ4008}.
     */
    private static void addFragment(BeanDefinitionRegistry registry, String name, RootBeanDefinition definition,
            ClassLoader classLoader) {
        Class<?> repositoryInterface = repositoryInterface(definition, classLoader);
        if (repositoryInterface == null || !ModelQueryRepository.class.isAssignableFrom(repositoryInterface)) {
            return;
        }
        if (definition.getPropertyValues().contains("customImplementation")) {
            throw new ModelQueryConfigurationException(MqCode.MQ4008, name + " (" + definition.getBeanClassName()
                    + ") already sets customImplementation; the starter cannot add the ModelQueryRepository fragment "
                    + "beside it, so leave the fragment to the repository's own implementation or extend "
                    + "ModelQueryRepositoryFactoryBean");
        }
        String fragmentsBeanName = name + ".modelQueryFragment";
        BeanDefinitionBuilder fragments = BeanDefinitionBuilder
                .genericBeanDefinition(ModelQueryRepositoryFragmentFactoryBean.class);
        fragments.addPropertyValue("repositoryInterface", repositoryInterface);
        // The properties Spring Data sets on a repository definition from @EnableJpaRepositories: without the
        // entityManager the fragment's own @PersistenceContext would fall back to an unqualified one (R-SPR-02).
        for (String property : List.of("entityManager", "lazyInit", "transactionManager")) {
            PropertyValue value = definition.getPropertyValues().getPropertyValue(property);
            if (value != null) {
                fragments.addPropertyValue(property, value.getValue());
            }
        }
        AbstractBeanDefinition fragmentsDefinition = (AbstractBeanDefinition) fragments.getBeanDefinition();
        // Not autowired: a ModelQueryRepository<?> injection must resolve to the repository, not its fragment.
        fragmentsDefinition.setAutowireCandidate(false);
        fragmentsDefinition.setLazyInit(definition.isLazyInit());
        registry.registerBeanDefinition(fragmentsBeanName, fragmentsDefinition);

        AbstractBeanDefinition swapped = clone(definition);
        swapped.getPropertyValues().add("customImplementation", new RuntimeBeanReference(fragmentsBeanName));
        registry.removeBeanDefinition(name);
        registry.registerBeanDefinition(name, swapped);
    }

    /**
     * The repository interface the definition names, or {@code null} when it is not a repository definition: the
     * registrar sets the definition's target type, resolving to the repository interface whenever the factory bean
     * class fixes its type arguments, and its constructor argument 0 names the interface when it does not.
     */
    private static Class<?> repositoryInterface(RootBeanDefinition root, ClassLoader classLoader) {
        Class<?> resolved = root.getResolvableType().as(RepositoryFactoryBeanSupport.class).resolveGeneric(0);
        if (resolved != null) {
            return resolved;
        }
        ConstructorArgumentValues.ValueHolder argument = root.getConstructorArgumentValues()
                .getIndexedArgumentValue(0, Class.class);
        Object value = argument == null ? null : argument.getValue();
        if (value instanceof Class<?> type) {
            return type;
        }
        return value instanceof TypedStringValue text ? load(text.getValue(), classLoader) : null;
    }

    private static Class<?> load(String className, ClassLoader classLoader) {
        try {
            return ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** A copy of {@code definition} under its own class, for the D-113 route, unlike {@link #copy}. */
    private static AbstractBeanDefinition clone(BeanDefinition definition) {
        return definition instanceof RootBeanDefinition root
                ? root.cloneBeanDefinition() : new GenericBeanDefinition(definition);
    }

    private static AbstractBeanDefinition copy(BeanDefinition definition) {
        if (definition instanceof RootBeanDefinition root) {
            RootBeanDefinition swapped = root.cloneBeanDefinition();
            swapped.setBeanClassName(ModelQueryRepositoryFactoryBean.class.getName());
            // getResolvableType, not getTargetType: the latter is the raw class, without the repository's generics.
            if (root.getTargetType() != null) {
                ResolvableType[] generics = root.getResolvableType().getGenerics();
                swapped.setTargetType(generics.length == 0
                        ? ResolvableType.forClass(ModelQueryRepositoryFactoryBean.class)
                        : ResolvableType.forClassWithGenerics(ModelQueryRepositoryFactoryBean.class, generics));
            }
            return swapped;
        }
        GenericBeanDefinition swapped = new GenericBeanDefinition(definition);
        swapped.setBeanClassName(ModelQueryRepositoryFactoryBean.class.getName());
        return swapped;
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
            ObjectProvider<WriteAssignment> assignments, ObjectProvider<ModelQueryConfigurer> configurer,
            ObjectProvider<ChunkTransactions> chunkTransactions, ConfigurableListableBeanFactory beanFactory) {
        ModelQueryConfig config = ModelQueryConfig.defaults().vendorProfiles(profiles.orderedStream().toList())
                .writeAssignments(assignments.orderedStream().toList());
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
        config = ifSet(config, properties.getBulkWrite().getConflictUpdateWhereOnAssignedColumns(),
                ModelQueryConfig::conflictUpdateWhereOnAssignedColumns);
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
     * reads: a {@link VendorProfile} or {@link WriteAssignment} bean the config does not hold, a
     * {@link ChunkTransactions} bean of the application's own it does not hold, or a {@code modelquery.*} property,
     * which only the starter's own config reads (R-SPR-11, R-SPR-13, D-54, D-118).
     *
     * @throws ModelQueryConfigurationException {@code MQ4006} on startup, naming what the config drops
     */
    @Bean
    SmartInitializingSingleton modelQueryConfigCheck(ObjectProvider<VendorProfile> profiles,
            ObjectProvider<WriteAssignment> assignments, ObjectProvider<ChunkTransactions> chunkTransactions,
            Environment environment, ConfigurableListableBeanFactory beanFactory) {
        return () -> {
            for (String name : beanFactory.getBeanNamesForType(ModelQueryConfig.class, false, false)) {
                if (!isOwn(beanFactory, name)) {
                    checkOwnConfig(name, beanFactory.getBean(name, ModelQueryConfig.class), profiles, assignments,
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
            ObjectProvider<WriteAssignment> assignments, ObjectProvider<ChunkTransactions> chunkTransactions,
            Environment environment) {
        List<String> dropped = new ArrayList<>();
        profiles.orderedStream()
                .filter(profile -> config.vendorProfiles().stream().noneMatch(held -> held == profile))
                .forEach(profile -> dropped.add("VendorProfile bean " + profile.getClass().getName()));
        assignments.orderedStream()
                .filter(assignment -> config.writeAssignments().stream().noneMatch(held -> held == assignment))
                .forEach(assignment -> dropped.add("WriteAssignment bean " + assignment));
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
