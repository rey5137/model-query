package com.rey.modelquery.portability.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.Target;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    enum Two { A, B, C }

    enum One { ONLY }

    enum None {}

    record Code(String value) {}

    @Test
    void ac_ins_17_each_tabled_type_has_two_distinct_placeholders_of_that_type() {
        for (Class<?> type : List.of(Long.class, Integer.class, Short.class, Byte.class, Double.class, Float.class,
                BigDecimal.class, BigInteger.class, String.class, Character.class, Boolean.class, UUID.class,
                LocalDate.class, LocalTime.class, LocalDateTime.class, OffsetDateTime.class, Instant.class)) {
            List<Object> keys = Placeholders.of(type).orElseThrow();
            assertThat(keys).as(type.getName()).hasSize(2).doesNotHaveDuplicates()
                    .allSatisfy(key -> assertThat(key).isInstanceOf(type));
        }
    }

    @Test
    void ac_ins_17_a_primitive_key_type_is_boxed_first() {
        assertThat(Placeholders.of(long.class)).contains(List.of(0L, 1L));
        assertThat(Placeholders.of(int.class)).contains(List.of(0, 1));
        assertThat(Placeholders.of(boolean.class)).contains(List.of(false, true));
        assertThat(Placeholders.of(char.class)).contains(List.of('a', 'b'));
    }

    @Test
    void ac_ins_17_an_enum_gives_its_first_two_constants_or_its_only_one() {
        assertThat(Placeholders.of(Two.class)).contains(List.of(Two.A, Two.B));
        assertThat(Placeholders.of(One.class)).contains(List.of(One.ONLY));
    }

    @Test
    void ac_ins_17_an_empty_enum_a_value_object_and_a_byte_array_have_none() {
        assertThat(Placeholders.of(None.class)).isEmpty();
        assertThat(Placeholders.of(Code.class)).isEmpty();
        assertThat(Placeholders.of(byte[].class)).isEmpty();
    }

    @Test
    void ac_ins_17_a_child_line_names_its_path_after_the_label_and_any_page_statement() {
        Target h2 = Target.of(DatabaseVendor.H2, "2.3.232");
        CallResult call = new CallResult("TREE", h2, Outcome.REACHED, false, "select 1");
        assertThat(call.child()).isEmpty();
        assertThat(call.withChild("orders.lines").line())
                .isEqualTo("TREE child orders.lines on " + h2 + ": REACHED select 1");
        assertThat(call.withStatement(CallResult.Statement.CONTENT).withChild("orders").line())
                .isEqualTo("TREE [content] child orders on " + h2 + ": REACHED select 1");
        assertThat(call.withChild("orders").withStatement(CallResult.Statement.CONTENT).child()).isEqualTo("orders");
    }
}
