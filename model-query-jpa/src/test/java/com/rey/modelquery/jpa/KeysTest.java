package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The key-list clamp: the largest power of two within the IN-list and bind limits, so a provider padding the list to
 * the next power of two stays within them (engine/21 R-PAG-07, api/14 R-WRT-08, D-80).
 */
class KeysTest {

    /** The {@code OTHER} profile's limits (R-VND-06). */
    private final Keys other = new Keys(1_000, 2_000);

    @Test
    void ac_prf_03_the_clamp_is_the_largest_power_of_two_within_the_bind_budget() {
        assertThat(other.clamp(1_500, 1, OptionalInt.empty())).isEqualTo(256); // 500 binds left
        assertThat(other.clamp(1_488, 1, OptionalInt.empty())).isEqualTo(512); // exactly 512 left
        assertThat(other.clamp(0, 3, OptionalInt.empty())).isEqualTo(512); // 666 keys of three columns
    }

    @Test
    void ac_prf_03_the_clamp_is_the_largest_power_of_two_within_the_in_list_limit() {
        assertThat(other.clamp(0, 1, OptionalInt.empty())).isEqualTo(512);
        assertThat(new Keys(1_024, 4_096).clamp(0, 1, OptionalInt.empty())).isEqualTo(1_024);
        assertThat(new Keys(10_000, 100_000).clamp(0, 1, OptionalInt.empty())).isEqualTo(8_192);
    }

    @Test
    void ac_pag_09_a_configured_size_below_the_clamp_is_kept_and_one_above_it_is_clamped() {
        assertThat(other.clamp(0, 1, OptionalInt.of(300))).isEqualTo(300);
        assertThat(other.clamp(0, 1, OptionalInt.of(5_000))).isEqualTo(512);
        assertThat(other.clamp(1_500, 1, OptionalInt.of(300))).isEqualTo(256);
    }

    @Test
    void ac_prf_03_a_statement_whose_own_binds_leave_no_room_still_takes_one_key() {
        assertThat(other.clamp(1_999, 1, OptionalInt.empty())).isEqualTo(1);
        assertThat(other.clamp(2_000, 1, OptionalInt.empty())).isEqualTo(1);
        assertThat(other.clamp(2_500, 2, OptionalInt.of(10))).isEqualTo(1);
    }
}
