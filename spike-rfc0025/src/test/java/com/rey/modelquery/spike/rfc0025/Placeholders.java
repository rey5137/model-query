package com.rey.modelquery.spike.rfc0025;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Part C's placeholder rule: two distinct values of the key's Java type, from a fixed table; an enum's first two
 * constants, or its only one; anything else, an empty enum included, has none and gives a failing line naming the
 * type.
 */
final class Placeholders {
    private Placeholders() {}

    private static final Map<Class<?>, List<Object>> TABLE = Map.ofEntries(
            Map.entry(Long.class, List.of(0L, 1L)),
            Map.entry(Integer.class, List.of(0, 1)),
            Map.entry(Short.class, List.of((short) 0, (short) 1)),
            Map.entry(Byte.class, List.of((byte) 0, (byte) 1)),
            Map.entry(String.class, List.of("a", "b")),
            Map.entry(Character.class, List.of('a', 'b')),
            Map.entry(Boolean.class, List.of(false, true)),
            Map.entry(BigDecimal.class, List.of(BigDecimal.ZERO, BigDecimal.ONE)),
            Map.entry(BigInteger.class, List.of(BigInteger.ZERO, BigInteger.ONE)),
            Map.entry(UUID.class, List.of(new UUID(0, 0), new UUID(0, 1))),
            Map.entry(LocalDate.class, List.of(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2))),
            Map.entry(LocalDateTime.class, List.of(LocalDateTime.of(2000, 1, 1, 0, 0),
                    LocalDateTime.of(2000, 1, 2, 0, 0))),
            Map.entry(Instant.class, List.of(Instant.EPOCH, Instant.EPOCH.plusSeconds(1))));

    /** Two distinct placeholders of {@code type}, or empty when the rule has none. */
    static Optional<List<Object>> of(Class<?> type) {
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            // An enum of one constant has one key in production too, so its one constant is the whole shape.
            return constants.length == 0 ? Optional.empty()
                    : Optional.of(Arrays.asList(Arrays.copyOf(constants, Math.min(2, constants.length))));
        }
        return Optional.ofNullable(TABLE.get(type));
    }

    /** {@code type} boxed, as the key's attribute type is read. */
    static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return type == long.class ? Long.class : type == int.class ? Integer.class : type == short.class ? Short.class
                : type == byte.class ? Byte.class : type == boolean.class ? Boolean.class
                : type == char.class ? Character.class : type == double.class ? Double.class : Float.class;
    }
}
