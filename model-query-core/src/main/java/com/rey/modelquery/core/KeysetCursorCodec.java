package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * The keyset cursor's frame and value codecs (R-PAG-18). A cursor is URL-safe base64 without padding over
 * {@code [version=1][fingerprint 8 bytes][key count][per value: kind byte + length-prefixed bytes][CRC32C 4 bytes]}.
 * The value kinds are the closed codec set of R-PAG-17; an enum is carried by its {@link Enum#name()}, which the
 * executor resolves against the column. Never Java serialization.
 *
 * @implSpec R-PAG-17, R-PAG-18, D-105, D-110
 */
@EngineFacing
@Incubating
public final class KeysetCursorCodec {

    /** The most characters a cursor may hold; a longer input is {@code MQ2208} (R-PAG-18). */
    public static final int MAX_CURSOR_LENGTH = 8192;

    /** The only version this release writes. */
    private static final byte VERSION = 1;
    /** The fingerprint's length. */
    private static final int FINGERPRINT_LENGTH = 8;

    private KeysetCursorCodec() {
    }

    /** A decoded cursor: the order fingerprint and the values. */
    record Decoded(byte[] fingerprint, Object[] values) {

        /** Defensive copies, so a decoded cursor cannot be mutated. */
        public Decoded {
            fingerprint = fingerprint.clone();
            values = values.clone();
        }

        @Override
        public byte[] fingerprint() {
            return fingerprint.clone();
        }

        @Override
        public Object[] values() {
            return values.clone();
        }
    }

    /** Whether a column's attribute type can be carried in a cursor (R-PAG-17). */
    public static boolean supports(Class<?> attributeType) {
        Objects.requireNonNull(attributeType, "attributeType");
        return supportsType(attributeType);
    }

    /** Whether a value of {@code attributeType} has a codec. An enum is carried as its {@link Enum#name()}. */
    static boolean supportsType(Class<?> attributeType) {
        Class<?> boxed = ColumnField.boxed(attributeType);
        if (boxed.isEnum()) {
            return true;
        }
        for (Kind kind : Kind.values()) {
            if (kind.type == boxed) {
                return true;
            }
        }
        return false;
    }

    /**
     * Encodes {@code fingerprint} and {@code values} as a cursor. {@code values} are in keyset order, each a value an
     * {@link OrderField}'s column returns, or {@code null}.
     */
    public static String encode(byte[] fingerprint, Object[] values) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(values, "values");
        if (fingerprint.length != FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException("fingerprint is " + fingerprint.length + " bytes, not "
                    + FINGERPRINT_LENGTH);
        }
        if (values.length > 0xFFFF) {
            throw new IllegalArgumentException(values.length + " key values do not fit a cursor");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.write(fingerprint, 0, FINGERPRINT_LENGTH);
            out.writeShort(values.length);
            for (Object value : values) {
                Kind kind = value == null ? Kind.NULL : kindOf(value);
                byte[] payload = kind.write(value);
                out.writeByte(kind.tag);
                out.writeInt(payload.length);
                out.write(payload);
            }
            out.flush();
            byte[] frame = bytes.toByteArray();
            CRC32C crc = new CRC32C();
            crc.update(frame);
            ByteArrayOutputStream all = new ByteArrayOutputStream(frame.length + 4);
            all.write(frame);
            all.write((int) (crc.getValue() >> 24));
            all.write((int) (crc.getValue() >> 16));
            all.write((int) (crc.getValue() >> 8));
            all.write((int) crc.getValue());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(all.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The bytes {@code value} takes in a cursor's frame, so a cursor over the cap can name its longest column. */
    public static int encodedLength(Object value) {
        return value == null ? 0 : kindOf(value).write(value).length;
    }

    /**
     * Decodes {@code cursor}, or throws {@code MQ2208} naming the reason. The whole cursor is never echoed.
     *
     * @implSpec R-PAG-18
     */
    static Decoded decode(String cursor) {
        if (cursor == null) {
            throw malformed("the cursor is null");
        }
        if (cursor.isBlank()) {
            throw malformed("the cursor is blank");
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw malformed("the cursor is longer than " + MAX_CURSOR_LENGTH + " characters");
        }
        byte[] all;
        try {
            all = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            throw malformed("it is not URL-safe base64 without padding");
        }
        if (all.length < 1 + FINGERPRINT_LENGTH + 2 + 4) {
            throw malformed("it is truncated");
        }
        int body = all.length - 4;
        CRC32C crc = new CRC32C();
        crc.update(all, 0, body);
        int stored = ((all[body] & 0xFF) << 24) | ((all[body + 1] & 0xFF) << 16)
                | ((all[body + 2] & 0xFF) << 8) | (all[body + 3] & 0xFF);
        if ((int) crc.getValue() != stored) {
            throw malformed("its checksum does not match");
        }
        if (all[0] != VERSION) {
            throw malformed("its version " + (all[0] & 0xFF) + " is unknown");
        }
        byte[] fingerprint = new byte[FINGERPRINT_LENGTH];
        System.arraycopy(all, 1, fingerprint, 0, FINGERPRINT_LENGTH);
        int count = ((all[1 + FINGERPRINT_LENGTH] & 0xFF) << 8) | (all[2 + FINGERPRINT_LENGTH] & 0xFF);
        Object[] values = new Object[count];
        int at = 1 + FINGERPRINT_LENGTH + 2;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(all, at, body - at))) {
            for (int i = 0; i < count; i++) {
                if (in.available() < 1) {
                    throw malformed("it is truncated");
                }
                byte tag = in.readByte();
                Kind kind = Kind.of(tag);
                if (kind == null) {
                    throw malformed("value " + (i + 1) + " has an unknown kind");
                }
                if (in.available() < 4) {
                    throw malformed("it is truncated");
                }
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    throw malformed("it is truncated");
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                values[i] = kind.read(payload);
            }
            if (in.available() != 0) {
                throw malformed("it holds trailing bytes");
            }
        } catch (IOException e) {
            throw malformed("it is truncated");
        }
        return new Decoded(fingerprint, values);
    }

    private static ModelQueryExecutionException malformed(String reason) {
        return new ModelQueryExecutionException(MqCode.MQ2208, "the keyset cursor is not one this library issued: "
                + reason + "; start again with KeysetSpec.first");
    }

    private static Kind kindOf(Object value) {
        for (Kind kind : Kind.values()) {
            if (kind.handles(value)) {
                return kind;
            }
        }
        throw new ModelQueryExecutionException(MqCode.MQ2210, "a keyset value of type "
                + value.getClass().getName() + " cannot be carried in a cursor");
    }

    /** The closed codec set of R-PAG-17. {@code type} is the value's boxed class; {@code NULL} has none. */
    private enum Kind {
        NULL(0, null),
        STRING(1, String.class),
        CHARACTER(2, Character.class),
        BOOLEAN(3, Boolean.class),
        BYTE(4, Byte.class),
        SHORT(5, Short.class),
        INTEGER(6, Integer.class),
        LONG(7, Long.class),
        BIG_INTEGER(8, BigInteger.class),
        BIG_DECIMAL(9, BigDecimal.class),
        UUID(10, UUID.class),
        ENUM(11, null),
        LOCAL_DATE(12, LocalDate.class),
        LOCAL_TIME(13, LocalTime.class),
        LOCAL_DATE_TIME(14, LocalDateTime.class),
        INSTANT(15, Instant.class),
        OFFSET_DATE_TIME(16, OffsetDateTime.class),
        OFFSET_TIME(17, OffsetTime.class),
        ZONED_DATE_TIME(18, ZonedDateTime.class),
        // The java.sql subtypes precede java.util.Date, whose isInstance accepts each of them.
        SQL_DATE(20, java.sql.Date.class),
        SQL_TIME(21, Time.class),
        TIMESTAMP(22, Timestamp.class),
        UTIL_DATE(19, java.util.Date.class),
        BYTE_ARRAY(23, byte[].class);

        private final byte tag;
        private final Class<?> type;

        Kind(int tag, Class<?> type) {
            this.tag = (byte) tag;
            this.type = type;
        }

        boolean handles(Object value) {
            if (this == ENUM) {
                return value instanceof Enum<?>;
            }
            return type != null && type.isInstance(value);
        }

        byte[] write(Object value) {
            if (this == NULL) {
                return new byte[0];
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                switch (this) {
                    case STRING -> out.write(((String) value).getBytes(StandardCharsets.UTF_8));
                    case CHARACTER -> out.writeChar((Character) value);
                    case BOOLEAN -> out.writeBoolean((Boolean) value);
                    case BYTE -> out.writeByte((Byte) value);
                    case SHORT -> out.writeShort((Short) value);
                    case INTEGER -> out.writeInt((Integer) value);
                    case LONG -> out.writeLong((Long) value);
                    case BIG_INTEGER -> out.write(((BigInteger) value).toByteArray());
                    case BIG_DECIMAL -> out.write(((BigDecimal) value).toString().getBytes(StandardCharsets.UTF_8));
                    case UUID -> {
                        UUID uuid = (UUID) value;
                        out.writeLong(uuid.getMostSignificantBits());
                        out.writeLong(uuid.getLeastSignificantBits());
                    }
                    case ENUM -> out.write(((Enum<?>) value).name().getBytes(StandardCharsets.UTF_8));
                    case LOCAL_DATE -> out.writeLong(((LocalDate) value).toEpochDay());
                    case LOCAL_TIME -> out.writeLong(((LocalTime) value).toNanoOfDay());
                    case LOCAL_DATE_TIME -> {
                        LocalDateTime dateTime = (LocalDateTime) value;
                        out.writeLong(dateTime.toLocalDate().toEpochDay());
                        out.writeLong(dateTime.toLocalTime().toNanoOfDay());
                    }
                    case INSTANT -> {
                        Instant instant = (Instant) value;
                        out.writeLong(instant.getEpochSecond());
                        out.writeInt(instant.getNano());
                    }
                    case OFFSET_DATE_TIME -> {
                        OffsetDateTime dateTime = (OffsetDateTime) value;
                        out.writeLong(dateTime.toEpochSecond());
                        out.writeInt(dateTime.getNano());
                        out.writeInt(dateTime.getOffset().getTotalSeconds());
                    }
                    case OFFSET_TIME -> {
                        OffsetTime time = (OffsetTime) value;
                        out.writeLong(time.toLocalTime().toNanoOfDay());
                        out.writeInt(time.getOffset().getTotalSeconds());
                    }
                    case ZONED_DATE_TIME -> {
                        ZonedDateTime dateTime = (ZonedDateTime) value;
                        out.writeLong(dateTime.toEpochSecond());
                        out.writeInt(dateTime.getNano());
                        out.write(dateTime.getZone().getId().getBytes(StandardCharsets.UTF_8));
                    }
                    case UTIL_DATE -> out.writeLong(((java.util.Date) value).getTime());
                    case SQL_DATE -> out.writeLong(((java.sql.Date) value).toLocalDate().toEpochDay());
                    case SQL_TIME -> out.writeLong(((Time) value).getTime());
                    case TIMESTAMP -> {
                        Timestamp timestamp = (Timestamp) value;
                        out.writeLong(timestamp.getTime());
                        out.writeInt(timestamp.getNanos());
                    }
                    case BYTE_ARRAY -> out.write((byte[]) value);
                    default -> throw new IllegalStateException(name());
                }
                out.flush();
                return bytes.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Object read(byte[] payload) {
            if (this == NULL) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                return switch (this) {
                    case STRING -> StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
                    case CHARACTER -> in.readChar();
                    case BOOLEAN -> in.readBoolean();
                    case BYTE -> in.readByte();
                    case SHORT -> in.readShort();
                    case INTEGER -> in.readInt();
                    case LONG -> in.readLong();
                    case BIG_INTEGER -> new BigInteger(payload);
                    case BIG_DECIMAL -> new BigDecimal(new String(payload, StandardCharsets.UTF_8));
                    case UUID -> new UUID(in.readLong(), in.readLong());
                    case ENUM -> new String(payload, StandardCharsets.UTF_8);
                    case LOCAL_DATE -> LocalDate.ofEpochDay(in.readLong());
                    case LOCAL_TIME -> LocalTime.ofNanoOfDay(in.readLong());
                    case LOCAL_DATE_TIME -> LocalDateTime.of(LocalDate.ofEpochDay(in.readLong()),
                            LocalTime.ofNanoOfDay(in.readLong()));
                    case INSTANT -> Instant.ofEpochSecond(in.readLong(), in.readInt());
                    case OFFSET_DATE_TIME -> OffsetDateTime.ofInstant(
                            Instant.ofEpochSecond(in.readLong(), in.readInt()),
                            ZoneOffset.ofTotalSeconds(in.readInt()));
                    case OFFSET_TIME -> OffsetTime.of(LocalTime.ofNanoOfDay(in.readLong()),
                            ZoneOffset.ofTotalSeconds(in.readInt()));
                    case ZONED_DATE_TIME -> ZonedDateTime.ofInstant(
                            Instant.ofEpochSecond(in.readLong(), in.readInt()),
                            ZoneId.of(new String(payload, 12, payload.length - 12, StandardCharsets.UTF_8)));
                    case UTIL_DATE -> new java.util.Date(in.readLong());
                    case SQL_DATE -> java.sql.Date.valueOf(LocalDate.ofEpochDay(in.readLong()));
                    case SQL_TIME -> new Time(in.readLong());
                    case TIMESTAMP -> setNanos(in.readLong(), in.readInt());
                    case BYTE_ARRAY -> payload;
                    default -> throw new IllegalStateException(name());
                };
            } catch (IOException e) {
                throw malformed("a value fails to decode");
            } catch (RuntimeException e) {
                throw malformed("a value fails to decode");
            }
        }

        private static Timestamp setNanos(long millis, int nanos) {
            Timestamp timestamp = new Timestamp(millis);
            timestamp.setNanos(nanos);
            return timestamp;
        }

        static Kind of(byte tag) {
            for (Kind kind : values()) {
                if (kind.tag == tag) {
                    return kind;
                }
            }
            return null;
        }
    }
}
