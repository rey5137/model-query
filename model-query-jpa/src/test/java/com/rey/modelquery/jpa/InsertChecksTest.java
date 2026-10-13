package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The conflict-clause checks an insert makes against the vendor's profile facts (R-WRT-52, R-VND-14). */
class InsertChecksTest {

    @Test
    void ac_wrt_45_a_vendor_whose_profile_lacks_the_fact_refuses_on_constraint_and_the_arbiter() {
        assertThatCode(() -> InsertChecks.checkConstraint("Insert", "uq_item_sku", true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InsertChecks.checkConstraint("Insert", "uq_item_sku", false))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
                    assertThat(e.code()).isEqualTo(MqCode.MQ1804);
                    assertThat(e.getMessage()).contains("VendorProfile#conflictOnConstraint()");
                });

        assertThatCode(() -> InsertChecks.checkArbiter("Insert", true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InsertChecks.checkArbiter("Insert", false))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
                    assertThat(e.code()).isEqualTo(MqCode.MQ1804);
                    assertThat(e.getMessage()).contains("VendorProfile#conflictArbiterPredicate()");
                });
    }

    /** A partial unique index has no JPA mapping, so an arbiter's key is not checked against the declared keys. */
    @Test
    void ac_wrt_45_an_arbiter_target_skips_the_check_against_the_mappings_declared_unique_keys() {
        List<Set<String>> declared = List.of(Set.of("id"));
        assertThatCode(() -> InsertChecks.checkConflict("Insert", List.of("sku"), declared, true, false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> InsertChecks.checkConflict("Insert", List.of("sku"), declared, true, true))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
                    assertThat(e.code()).isEqualTo(MqCode.MQ1804);
                    assertThat(e.getMessage()).contains("onConflict[sku] names no unique key of the mapping");
                });
    }
}
