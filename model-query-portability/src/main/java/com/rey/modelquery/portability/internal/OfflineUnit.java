package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The persistence unit a dry-run factory boots: a unit named in {@code persistence.xml}, or a list of entity classes
 * with the mapping files and properties a class list does not carry (a naming strategy, converters, {@code orm.xml}).
 * The properties lie beneath the dry run's own overrides. Equal units share a cached factory per target.
 *
 * @implSpec R-INS-10
 */
@Incubating
public final class OfflineUnit {

    private final String name;
    private final List<String> classNames;
    private final List<String> mappingFiles;
    private final Map<String, Object> properties;

    private OfflineUnit(String name, List<String> classNames, List<String> mappingFiles, Map<String, ?> properties) {
        this.name = name;
        this.classNames = classNames;
        this.mappingFiles = mappingFiles;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** The unit {@code name} of {@code persistence.xml}, booted with {@code properties} beneath the overrides. */
    public static OfflineUnit named(String name, Map<String, ?> properties) {
        return new OfflineUnit(Objects.requireNonNull(name, "name"), List.of(), List.of(),
                Objects.requireNonNull(properties, "properties"));
    }

    /**
     * A unit of {@code classes}, in any order, with {@code mappingFiles} and {@code properties} beneath the overrides.
     */
    public static OfflineUnit ofClasses(Collection<Class<?>> classes, List<String> mappingFiles,
            Map<String, ?> properties) {
        List<String> names = Objects.requireNonNull(classes, "classes").stream().map(Class::getName).sorted()
                .distinct().toList();
        List<String> files = List.copyOf(Objects.requireNonNull(mappingFiles, "mappingFiles"));
        return new OfflineUnit(null, names, files, Objects.requireNonNull(properties, "properties"));
    }

    /** The persistence unit name, or empty for a class list. */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    /** The entity class names of a class list, sorted; empty for a named unit. */
    public List<String> classNames() {
        return classNames;
    }

    /** The mapping files of a class list. */
    public List<String> mappingFiles() {
        return mappingFiles;
    }

    /** The application's own properties. */
    public Map<String, Object> properties() {
        return properties;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OfflineUnit that && Objects.equals(name, that.name)
                && classNames.equals(that.classNames) && mappingFiles.equals(that.mappingFiles)
                && properties.equals(that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, classNames, mappingFiles, properties);
    }

    @Override
    public String toString() {
        return name != null ? "unit " + name : "classes " + classNames;
    }
}
