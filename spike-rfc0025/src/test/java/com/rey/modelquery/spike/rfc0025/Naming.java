package com.rey.modelquery.spike.rfc0025;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * R-INS-16's spike item: whether a booted factory's {@code getProperties()} returns the naming strategies Boot passes
 * the provider, and what spring-orm's factory bean reports as the unit. Boots an in-memory H2 unit, no statement run.
 */
final class Naming {
    static final String PHYSICAL = "hibernate.physical_naming_strategy";
    static final String IMPLICIT = "hibernate.implicit_naming_strategy";

    private final Cells cells;

    Naming(Cells cells) {
        this.cells = cells;
    }

    void run() {
        String physical = CamelCaseToUnderscoresNamingStrategy.class.getName();
        String implicit = SpringImplicitNamingStrategy.class.getName();
        // Boot's defaults, as class-name strings, through the container contract.
        Map<String, Object> strings = new HashMap<>(Map.of(PHYSICAL, physical, IMPLICIT, implicit));
        EntityManagerFactory emf = container(strings);
        try {
            Map<String, Object> props = emf.getProperties();
            cells.record("ANY", "4", "container-strings", physical.equals(props.get(PHYSICAL))
                    && implicit.equals(props.get(IMPLICIT)), "physical=" + describe(props.get(PHYSICAL))
                    + "; implicit=" + describe(props.get(IMPLICIT)) + "; " + props.size() + " properties");
        } finally {
            emf.close();
        }
        // Instance-valued, as Boot passes a naming-strategy bean.
        var physicalBean = new CamelCaseToUnderscoresNamingStrategy();
        var implicitBean = new SpringImplicitNamingStrategy();
        emf = container(new HashMap<>(Map.of(PHYSICAL, physicalBean, IMPLICIT, implicitBean)));
        try {
            Map<String, Object> props = emf.getProperties();
            cells.record("ANY", "4", "container-instances", props.get(PHYSICAL) == physicalBean
                    && props.get(IMPLICIT) == implicitBean, "physical=" + describe(props.get(PHYSICAL))
                    + " (same instance: " + (props.get(PHYSICAL) == physicalBean) + "); implicit="
                    + describe(props.get(IMPLICIT)) + " (same instance: " + (props.get(IMPLICIT) == implicitBean)
                    + ")");
        } finally {
            emf.close();
        }
        // Neither key: what the provider reports as its own default.
        emf = container(new HashMap<>());
        try {
            Map<String, Object> props = emf.getProperties();
            cells.record("ANY", "4", "container-no-naming-keys", !props.containsKey(PHYSICAL)
                    && !props.containsKey(IMPLICIT), "physical=" + describe(props.get(PHYSICAL)) + "; implicit="
                    + describe(props.get(IMPLICIT)));
        } finally {
            emf.close();
        }
        factoryBean(physical, implicit);
    }

    /** spring-orm's factory bean over a scanned package and one mapping file, as a Boot application boots it. */
    private void factoryBean(String physical, String implicit) {
        var bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(h2());
        bean.setPersistenceProvider(new HibernatePersistenceProvider());
        bean.setPackagesToScan(Naming.class.getPackageName());
        bean.setMappingResources("META-INF/spike-orm.xml");
        bean.setJpaPropertyMap(Map.of(PHYSICAL, physical, IMPLICIT, implicit, "hibernate.hbm2ddl.auto", "none"));
        bean.afterPropertiesSet();
        try {
            PersistenceUnitInfo unit = bean.getPersistenceUnitInfo();
            List<String> classes = unit.getManagedClassNames();
            boolean converter = classes.contains(Entities.CodeConverter.class.getName());
            boolean entities = Entities.ALL.stream().allMatch(type -> classes.contains(type.getName()));
            cells.record("ANY", "4", "factory-bean-managed-classes", converter && entities, classes.size()
                    + " classes; every entity: " + entities + "; autoApply converter CodeConverter: " + converter
                    + "; embeddable EmbKey: " + classes.contains(Entities.EmbKey.class.getName()) + "; names="
                    + classes.stream().map(name -> name.substring(name.lastIndexOf('.') + 1)).sorted().toList());
            cells.record("ANY", "4", "factory-bean-mapping-files",
                    unit.getMappingFileNames().contains("META-INF/spike-orm.xml"), "mappingFiles="
                    + unit.getMappingFileNames() + "; excludeUnlistedClasses=" + unit.excludeUnlistedClasses()
                    + "; unit properties=" + unit.getProperties().stringPropertyNames());
            Map<String, Object> props = bean.getObject().getProperties();
            cells.record("ANY", "4", "factory-bean-emf-properties", physical.equals(props.get(PHYSICAL))
                    && implicit.equals(props.get(IMPLICIT)), "physical=" + describe(props.get(PHYSICAL))
                    + "; implicit=" + describe(props.get(IMPLICIT)) + "; via " + bean.getObject().getClass()
                    .getSimpleName());
            // What R-INS-16 would drop: the values that aren't strings, numbers, booleans or enums.
            var instances = new java.util.TreeMap<String, String>();
            props.forEach((key, value) -> {
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean
                        || value instanceof Enum<?>)) {
                    instances.put(key, value == null ? "null" : value.getClass().getSimpleName());
                }
            });
            cells.record("ANY", "4", "factory-bean-instance-valued-keys", true, props.size() + " properties, "
                    + instances.size() + " instance-valued: " + instances);
        } finally {
            bean.destroy();
        }
    }

    private static EntityManagerFactory container(Map<String, Object> properties) {
        properties.put("hibernate.hbm2ddl.auto", "none");
        return new HibernatePersistenceProvider().createContainerEntityManagerFactory(unit(h2()), properties);
    }

    private static DataSource h2() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rfc0025-naming;DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static PersistenceUnitInfo unit(DataSource dataSource) {
        List<String> names = Entities.ALL.stream().map(Class::getName).toList();
        ClassLoader loader = Naming.class.getClassLoader();
        return (PersistenceUnitInfo) Proxy.newProxyInstance(loader, new Class<?>[] {PersistenceUnitInfo.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getPersistenceUnitName", "toString" -> "rfc0025-naming";
                    case "getPersistenceProviderClassName" -> HibernatePersistenceProvider.class.getName();
                    case "getTransactionType" -> PersistenceUnitTransactionType.RESOURCE_LOCAL;
                    case "getManagedClassNames" -> names;
                    case "getMappingFileNames", "getJarFileUrls", "getQualifierAnnotationNames" -> List.of();
                    case "excludeUnlistedClasses" -> true;
                    case "getSharedCacheMode" -> SharedCacheMode.NONE;
                    case "getValidationMode" -> ValidationMode.NONE;
                    case "getProperties" -> new Properties();
                    case "getPersistenceXMLSchemaVersion" -> "3.0";
                    case "getClassLoader" -> loader;
                    case "getNonJtaDataSource" -> dataSource;
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> null;
                });
    }

    private static String describe(Object value) {
        return value == null ? "absent" : value instanceof String s ? "String " + s
                : "instance of " + value.getClass().getName();
    }
}
