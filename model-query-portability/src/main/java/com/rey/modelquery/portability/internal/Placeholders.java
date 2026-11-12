package com.rey.modelquery.portability.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The keys a child statement renders on with no parent row (D-224): two distinct values of the child key's declared
 * Java type, so the key match has a page's multi-key shape ({@code in (?,?)}); an enum's first two constants, or its
 * only one, the only key production can send. A type outside the table, an empty enum, a value object behind a
 * converter or {@code byte[]} among them, has none.
 *
 * @implSpec R-INS-17
 */
final class Placeholders {

    private static final Map<Class<?>, List<Object>> TABLE = Map.ofEntries(
            Map.entry(Long.class, List.of(0L, 1L)),
            Map.entry(Integer.class, List.of(0, 1)),
            Map.entry(Short.class, List.of((short) 0, (short) 1)),
            Map.entry(Byte.class, List.of((byte) 0, (byte) 1)),
            Map.entry(Double.class, List.of(0d, 1d)),
            Map.entry(Float.class, List.of(0f, 1f)),
            Map.entry(BigDecimal.class, List.of(BigDecimal.ZERO, BigDecimal.ONE)),
            Map.entry(BigInteger.class, List.of(BigInteger.ZERO, BigInteger.ONE)),
            Map.entry(String.class, List.of("a", "b")),
            Map.entry(Character.class, List.of('a', 'b')),
            Map.entry(Boolean.class, List.of(false, true)),
            Map.entry(UUID.class, List.of(new UUID(0, 0), new UUID(0, 1))),
            Map.entry(LocalDate.class, List.of(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2))),
            Map.entry(LocalTime.class, List.of(LocalTime.of(0, 0), LocalTime.of(0, 1))),
            Map.entry(LocalDateTime.class, List.of(LocalDateTime.of(2000, 1, 1, 0, 0),
                    LocalDateTime.of(2000, 1, 2, 0, 0))),
            Map.entry(OffsetDateTime.class, List.of(OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                    OffsetDateTime.of(2000, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC))),
            Map.entry(Instant.class, List.of(Instant.EPOCH, Instant.EPOCH.plusSeconds(1))));

    private Placeholders() {
    }

    /** The placeholder keys of {@code type}, boxed first: two, one for a one-constant enum, or empty for none. */
    static Optional<List<Object>> of(Class<?> type) {
        Class<?> boxed = boxed(type);
        if (boxed.isEnum()) {
            Object[] constants = boxed.getEnumConstants();
            return constants.length == 0 ? Optional.empty()
                    : Optional.of(Arrays.asList(Arrays.copyOf(constants, Math.min(2, constants.length))));
        }
        return Optional.ofNullable(TABLE.get(boxed));
    }

    /** {@code type}, boxed when primitive. */
    static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return type == long.class ? Long.class : type == int.class ? Integer.class : type == short.class ? Short.class
                : type == byte.class ? Byte.class : type == double.class ? Double.class
                : type == float.class ? Float.class : type == boolean.class ? Boolean.class : Character.class;
    }
}
