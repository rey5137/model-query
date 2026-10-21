package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateOperators#fullTextMatch}'s configuration check and cache, at the factory call; the rows and the
 * rendered statement are the TCK's (spec vendor/40 R-VND-23, D-173; TCK AC-FLT-32).
 */
class HibernateOperatorsTest {

    @Test
    void ac_flt_32_a_null_blank_or_non_identifier_configuration_is_mq1315_naming_it() {
        assertThatThrownBy(() -> HibernateOperators.fullTextMatch(null))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1315))
                .hasMessageContaining("fullTextMatch(null)");
        for (String configuration : Arrays.asList("", "  ", "english'", "a b", "1abc", "public.", ".english",
                "a.b.c", "english;", "eng-lish", "'english'", "english\n")) {
            assertThatThrownBy(() -> HibernateOperators.fullTextMatch(configuration)).as(configuration)
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1315))
                    .hasMessageContaining("fullTextMatch(\"" + configuration + "\")");
        }
    }

    @Test
    void ac_flt_32_a_plain_or_schema_qualified_identifier_is_accepted_and_cached_per_configuration() {
        FilterOperator<String, String> english = HibernateOperators.fullTextMatch("english");
        assertThat(HibernateOperators.fullTextMatch("english")).isSameAs(english);
        FilterOperator<String, String> custom = HibernateOperators.fullTextMatch("public.my_config");
        assertThat(HibernateOperators.fullTextMatch("public.my_config")).isSameAs(custom).isNotSameAs(english);
        assertThat(HibernateOperators.fullTextMatch("_Simple1")).isNotSameAs(english);
        assertThat(english.name()).isEqualTo("fullTextMatch(english)");
        assertThat(custom.name()).isEqualTo("fullTextMatch(public.my_config)");
    }
}
