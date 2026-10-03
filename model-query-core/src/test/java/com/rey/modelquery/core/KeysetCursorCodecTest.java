package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** The keyset cursor's frame and value codecs and the malformed-input rules (engine/21 R-PAG-17, R-PAG-18). */
class KeysetCursorCodecTest {

    private enum Colour { RED, GREEN }

    private static final byte[] FINGERPRINT = {(byte) 0x9A, 1, 2, 3, 4, 5, 6, 7};

    // AC-PAG-18

    @Test
    void ac_pag_18_every_supported_type_round_trips_through_the_cursor() {
        // Each value is decoded to its Java value; an enum arrives as its name, which the executor resolves.
        Object[] values = {
                "text", 'x', true, (byte) -3, (short) -300, -70000, 9_000_000_000L,
                new BigInteger("123456789012345678901234567890"),
                new BigDecimal("1.100"),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Colour.GREEN,
                LocalDate.of(2024, 2, 29), LocalTime.of(23, 59, 59, 123456789),
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 987654321),
                Instant.ofEpochSecond(1_700_000_000L, 123456789),
                OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 6, ZoneOffset.ofHoursMinutes(5, 30)),
                OffsetTime.of(3, 4, 5, 6, ZoneOffset.ofHours(-4)),
                ZonedDateTime.of(2024, 6, 7, 8, 9, 10, 11, ZoneId.of("Europe/Paris")),
                new java.util.Date(1_234_567_890L),
                java.sql.Date.valueOf(LocalDate.of(2020, 5, 6)),
                new java.sql.Time(25_689_500L), // 07:08:09.500 UTC: a Time keeps its milliseconds
                timestamp(1_234_567_890L, 987_654_321),
                new byte[] {1, 2, 3, (byte) 250}
        };
        Object[] expected = values.clone();
        expected[10] = "GREEN"; // an enum is carried by name() and decoded as that name

        String cursor = KeysetCursorCodec.encode(FINGERPRINT, values);
        KeysetCursorCodec.Decoded decoded = KeysetCursorCodec.decode(cursor);

        assertThat(decoded.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(decoded.values()).containsExactly(expected);
        assertThat(decoded.values()[8]).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) decoded.values()[8]).scale()).isEqualTo(3);
        assertThat(((java.sql.Timestamp) decoded.values()[21]).getNanos()).isEqualTo(987_654_321);
    }

    @Test
    void ac_pag_18_a_string_keeps_supplementary_characters_and_has_no_64_kb_limit() {
        String emoji = "a\uD83D\uDE00\u0000b";
        assertThat(KeysetCursorCodec.decode(KeysetCursorCodec.encode(FINGERPRINT, new Object[] {emoji})).values())
                .containsExactly(emoji);
        // The executor refuses an over-long cursor with MQ2210; the codec itself encodes and measures any length.
        String longText = "x".repeat(70_000);
        assertThat(KeysetCursorCodec.encodedLength(longText)).isEqualTo(70_000);
        assertThat(KeysetCursorCodec.encode(FINGERPRINT, new Object[] {longText}))
                .hasSizeGreaterThan(KeysetCursorCodec.MAX_CURSOR_LENGTH);
    }

    @Test
    void ac_pag_18_a_null_value_round_trips_and_the_cursor_is_url_safe_base64_without_padding() {
        Object[] values = {"a", null, 2};
        String cursor = KeysetCursorCodec.encode(FINGERPRINT, values);
        assertThat(cursor).doesNotContain("+", "/", "=");
        assertThat(Base64.getUrlDecoder().decode(cursor)).isNotEmpty();
        assertThat(KeysetCursorCodec.decode(cursor).values()).containsExactly("a", null, 2);
    }

    // AC-PAG-19

    @Test
    void ac_pag_19_every_malformed_input_throws_mq2208_at_keyset_spec_and_nothing_else() {
        String valid = KeysetCursorCodec.encode(FINGERPRINT, new Object[] {"a", 1});

        assertMq2208(() -> KeysetSpec.after(null, 5));
        assertMq2208(() -> KeysetSpec.after("", 5));
        assertMq2208(() -> KeysetSpec.after("   ", 5));
        assertMq2208(() -> KeysetSpec.after("not base64!", 5));
        assertMq2208(() -> KeysetSpec.after(valid.substring(0, valid.length() - 3), 5));
        assertMq2208(() -> KeysetSpec.after(mutate(valid, 0, 'A' == valid.charAt(0) ? 'B' : 'A'), 5));
        assertMq2208(() -> KeysetSpec.after("A".repeat(8193), 5));
        // An unknown version: version byte 2, CRC recomputed so only the version is wrong.
        assertMq2208(() -> KeysetSpec.after(withVersion(valid, (byte) 2), 5));
        // Trailing bytes after a valid frame: the checksum is recomputed so only the shape is wrong.
        assertMq2208(() -> KeysetSpec.after(withTrailingByte(valid), 5));
    }

    @Test
    void ac_pag_19_flipping_any_character_of_a_valid_cursor_throws_mq2208() {
        Object[] values = {"P001", 42, new BigDecimal("3.14"), Colour.RED};
        String valid = KeysetCursorCodec.encode(FINGERPRINT, values);
        char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        for (int i = 0; i < valid.length(); i++) {
            char original = valid.charAt(i);
            char replacement = original == alphabet[0] ? alphabet[1] : alphabet[0];
            String flipped = valid.substring(0, i) + replacement + valid.substring(i + 1);
            // A changed base64 character decodes to different bytes, so the CRC32C never matches.
            assertMq2208(() -> KeysetSpec.after(flipped, 5));
        }
    }

    private static String mutate(String cursor, int index, char replacement) {
        return cursor.substring(0, index) + replacement + cursor.substring(index + 1);
    }

    /** Re-encodes the frame with another version byte, keeping a matching CRC32C. */
    private static String withVersion(String cursor, byte version) {
        byte[] all = Base64.getUrlDecoder().decode(cursor);
        all[0] = version;
        return reencode(all);
    }

    /** Appends one byte before the CRC and recomputes it, so only the trailing-byte rule can reject it. */
    private static String withTrailingByte(String cursor) {
        byte[] frame = Base64.getUrlDecoder().decode(cursor);
        byte[] grown = new byte[frame.length + 1];
        System.arraycopy(frame, 0, grown, 0, frame.length - 4);
        grown[frame.length - 4] = 0;
        System.arraycopy(frame, frame.length - 4, grown, frame.length - 3, 4);
        return reencode(grown);
    }

    private static String reencode(byte[] all) {
        int body = all.length - 4;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(all, 0, body);
        long value = crc.getValue();
        all[body] = (byte) (value >> 24);
        all[body + 1] = (byte) (value >> 16);
        all[body + 2] = (byte) (value >> 8);
        all[body + 3] = (byte) value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    private static java.sql.Timestamp timestamp(long millis, int nanos) {
        java.sql.Timestamp timestamp = new java.sql.Timestamp(millis);
        timestamp.setNanos(nanos);
        return timestamp;
    }

    private static void assertMq2208(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2208));
    }
}
