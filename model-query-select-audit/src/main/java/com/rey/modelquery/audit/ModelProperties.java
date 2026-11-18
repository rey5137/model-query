package com.rey.modelquery.audit;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.TableField;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Metadata is Q-specific; selection is always measured from the actual row. */
final class ModelProperties {
    private static final ClassValue<ClassValue<Optional<ModelProperties>>> MAPPERS = new ClassValue<>() {
        @Override
        protected ClassValue<Optional<ModelProperties>> computeValue(Class<?> host) {
            return new ClassValue<>() {
                @Override
                protected Optional<ModelProperties> computeValue(Class<?> modelClass) {
                    return mapperProperties(host, modelClass);
                }
            };
        }
    };
    private static final Set<String> WARNED_HAND_WRITTEN = ConcurrentHashMap.newKeySet();
    private final ClassValue<Map<String, ModelProperties>> nested = new ClassValue<>() {
        @Override
        protected Map<String, ModelProperties> computeValue(Class<?> modelClass) {
            return new ConcurrentHashMap<>();
        }
    };
    private final Class<?> qClass;
    private final Class<?> rootModel;
    private final FieldIndex<?> fields;
    private final String prefix;
    private final Map<String, Integer> propertyBits;
    private final Map<String, TableField<?, ?>> joins;
    private final Set<String> mapped;
    private final Map<String, String> constants;

    private ModelProperties(Class<?> qClass, Class<?> rootModel, Class<?> modelClass, FieldIndex<?> fields,
            String prefix) {
        this.qClass = qClass;
        this.rootModel = rootModel;
        this.fields = fields;
        this.prefix = prefix;
        Map<String, Integer> bits = new LinkedHashMap<>();
        if (modelClass.isRecord()) {
            for (var component : modelClass.getRecordComponents()) {
                bits.put(component.getName(), bits.size());
            }
        } else {
            for (Method method : modelClass.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() != void.class
                        && !Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() != Object.class) {
                    String name = propertyName(method.getName());
                    if (!name.equals(method.getName()) && fields.select(prefix + name).isEmpty()
                            && fields.select(prefix + method.getName()).isPresent()) {
                        name = method.getName();
                    }
                    bits.computeIfAbsent(name, ignored -> bits.size());
                }
            }
        }
        this.propertyBits = Map.copyOf(bits);
        Map<String, TableField<?, ?>> tables = new LinkedHashMap<>();
        for (var field : qClass.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !TableField.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                TableField<?, ?> table = (TableField<?, ?>) field.get(null);
                if (table.presenceKey() == null) {
                    continue;
                }
                for (String property : bits.keySet()) {
                    String path = prefix + property;
                    boolean matches = table.presenceKey().columns().stream().allMatch(column ->
                            fields.select(path + "." + column.property().orElse(column.name()))
                                    .filter(mapped -> mapped.equals(reRoot(column, rootModel, table))).isPresent());
                    if (matches) {
                        tables.put(property, table);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read join metadata in " + qClass.getName(), e);
            }
        }
        this.joins = Map.copyOf(tables);
        this.mapped = bits.keySet().stream().filter(property -> joins.containsKey(property)
                || fields.select(prefix + property).isPresent() || fields.child(prefix + property).isPresent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, String> names = new LinkedHashMap<>();
        for (var field : qClass.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !com.rey.modelquery.core.SelectField.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Object constant = field.get(null);
                if (constant == null) {
                    continue;
                }
                for (String property : bits.keySet()) {
                    if (fields.select(prefix + property).filter(constant::equals).isPresent()) {
                        names.putIfAbsent(property, qClass.getSimpleName() + "." + field.getName());
                    }
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read selection metadata in " + qClass.getName(), e);
            }
        }
        this.constants = Map.copyOf(names);
    }

    Map<String, String> constants() { return constants; }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ColumnField<?, ?, ?> reRoot(ColumnField column, Class<?> model, TableField table) {
        return column.withTable(model, table);
    }

    static ModelProperties of(ModelQuery<?, ?, ?> query, Class<?> modelClass) {
        Class<?> host = query.mapper().getClass().getNestHost();
        return MAPPERS.get(host).get(modelClass).orElse(null);
    }

    private static Optional<ModelProperties> mapperProperties(Class<?> host, Class<?> modelClass) {
        if (!hasFieldsMethod(host)) {
            if (WARNED_HAND_WRITTEN.add(modelClass.getName())) {
                System.getLogger(ModelProperties.class.getName()).log(System.Logger.Level.WARNING,
                        "Hand-written mapper for " + modelClass.getName() + "; models are not audited");
            }
            return Optional.empty();
        }
        try {
            return Optional.of(new ModelProperties(host, modelClass, modelClass,
                    (FieldIndex<?>) host.getMethod("fields").invoke(null), ""));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read fields() in " + host.getName(), e);
        }
    }

    private static final ClassValue<Boolean> HAS_FIELDS_METHOD = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("fields");
                return Modifier.isStatic(method.getModifiers())
                        && FieldIndex.class.isAssignableFrom(method.getReturnType());
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    static boolean hasFieldsMethod(Class<?> type) {
        return HAS_FIELDS_METHOD.get(type);
    }

    ModelProperties nested(String property, Class<?> modelClass) {
        String path = prefix + property + ".";
        return nested.get(modelClass).computeIfAbsent(path, ignored ->
                new ModelProperties(qClass, rootModel, modelClass, fields, path));
    }

    Map<String, Integer> propertyBits() {
        return propertyBits;
    }

    Set<String> mapped() {
        return mapped;
    }

    void recordMappingReads(Tag tag, Row row) {
        propertyBits.keySet().forEach(property -> fields.select(prefix + property)
                .filter(field -> ReadAccess.wasRowRead(row, field)).ifPresent(ignored -> tag.read(property)));
    }

    Map<String, TableField<?, ?>> joins() {
        return joins;
    }

    String prefix() {
        return prefix;
    }

    BitSet selectionBitSet(ModelQuery<?, ?, ?> query, Row row) {
        BitSet selected = new BitSet(propertyBits.size());
        propertyBits.forEach((property, bit) -> {
            TableField<?, ?> join = joins.get(property);
            if (join != null) {
                Row scoped = row.scoped(join);
                if (join.presenceKey().columns().stream().allMatch(scoped::isSelected)) {
                    selected.set(bit);
                }
            } else if (fields.child(prefix + property).isPresent()) {
                if (prefix.isEmpty() && query.fetch().stream().flatMap(plan -> plan.childLoads().stream())
                        .anyMatch(load -> load.field().name().equals(property))) {
                    selected.set(bit);
                }
            } else if (fields.select(prefix + property).filter(row::isSelected).isPresent()) {
                selected.set(bit);
            }
        });
        return selected;
    }

    static Object read(Object model, String property) {
        try {
            Method getter;
            try {
                getter = model.getClass().getMethod(property);
            } catch (NoSuchMethodException e) {
                getter = model.getClass().getMethod("get" + Character.toUpperCase(property.charAt(0))
                        + property.substring(1));
            }
            return getter.invoke(model);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read join " + model.getClass().getName() + "." + property, e);
        }
    }

    private static String propertyName(String method) {
        int prefix = method.startsWith("get") ? 3 : method.startsWith("is") ? 2 : 0;
        return prefix > 0 && method.length() > prefix && Character.isUpperCase(method.charAt(prefix))
                ? Character.toLowerCase(method.charAt(prefix)) + method.substring(prefix + 1) : method;
    }
}
