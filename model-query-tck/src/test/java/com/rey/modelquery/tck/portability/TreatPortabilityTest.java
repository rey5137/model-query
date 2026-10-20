package com.rey.modelquery.tck.portability;

import static com.rey.modelquery.tck.col.TreatTestSupport.ID;
import static com.rey.modelquery.tck.col.TreatTestSupport.NUMBER;
import static com.rey.modelquery.tck.col.TreatTestSupport.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.tck.col.TreatTestSupport.Bank;
import com.rey.modelquery.tck.col.TreatTestSupport.Card;
import com.rey.modelquery.tck.col.TreatTestSupport.Holder;
import com.rey.modelquery.tck.col.TreatTestSupport.Payment;
import com.rey.modelquery.tck.col.TreatTestSupport.Premium;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC-VND-17's offline half: per declared target, without a database, each treat site is reported from the renderer's
 * table alone, {@code MQ4014} without a renderer and {@code MQ4016} outside its table; the statement preflight runs
 * only at execution (spec vendor/40 R-VND-26, D-163).
 */
class TreatPortabilityTest {

    private static final Target H2 = Target.of(DatabaseVendor.H2, "2.3.232");
    private static final Target POSTGRESQL = Target.of(DatabaseVendor.POSTGRESQL, "15.4");
    private static final Target MYSQL = Target.of(DatabaseVendor.MYSQL, "8.0.36");
    private static final List<Target> TARGETS = List.of(H2, POSTGRESQL, MYSQL);

    /** A treated column read in the content statement: an admitted SINGLE_TABLE cell. */
    private static final ModelQuery<Payment, Long, String> NUMBERS = ModelQuery
            .builder(ROOT, row -> row.get(ID) + ":" + row.get(NUMBER)).select(SelectSet.of(ID, NUMBER))
            .primaryKey(PrimaryKey.of(ID)).build();

    /** A treated predicate in a bulk write: no WRITE cell is in the table. */
    private static final ModelDelete<Payment, ?> UNNUMBERED = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID))
            .where(f -> f.isNull(NUMBER)).build();

    static final class Swept {
        static final ModelQuery<Payment, Long, String> NUMBERS = TreatPortabilityTest.NUMBERS;
        static final ModelDelete<Payment, ?> UNNUMBERED = TreatPortabilityTest.UNNUMBERED;
    }

    @Test
    void ac_vnd_17_an_admitted_site_reaches_its_statement_on_every_target() {
        check().call("NUMBERS", Payment.class, x -> x.list(NUMBERS, Limit.of(10)))
                .assertPortable();
    }

    @Test
    void ac_vnd_17_a_site_outside_the_table_is_mq4016_on_every_target_without_a_database() {
        List<String> lines = report(check()
                .call("NUMBERS", Payment.class, x -> x.list(NUMBERS, Limit.of(10)))
                .call("UNNUMBERED", Payment.class, x -> x.delete(UNNUMBERED)));

        assertThat(lines.get(0)).isEqualTo("Not portable: 3 of 6 results failed");
        for (Target target : TARGETS) {
            assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("NUMBERS on " + target + ": REACHED"));
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("UNNUMBERED on " + target + ": REFUSED MQ4016").contains("WRITE"));
        }
    }

    @Test
    void ac_vnd_17_without_a_treat_renderer_every_site_is_mq4014_on_every_target() {
        List<String> lines = new ArrayList<>();
        // This provider boots offline through Hibernate's properties; its inherited treats(emf) default is empty.
        NoSourceProviderSupport.serving(() -> lines.addAll(report(check()
                .call("NUMBERS", Payment.class, x -> x.list(NUMBERS, Limit.of(10))))));

        assertThat(lines).hasSize(4);
        for (Target target : TARGETS) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("NUMBERS on " + target + ": REFUSED MQ4014").contains("TreatRenderer"));
        }
    }

    @Test
    void ac_vnd_17_a_sweep_reports_each_definitions_treat_sites_per_target() {
        List<String> lines = report(check().sweep(Swept.class));

        assertThat(lines.get(0)).isEqualTo("Not portable: 3 of 6 results failed");
        for (Target target : TARGETS) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("Swept.NUMBERS list on " + target + ": REACHED"));
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("Swept.UNNUMBERED delete on " + target + ": REFUSED MQ4016"));
        }
    }

    private static PortabilityCheck check() {
        return PortabilityCheck.of(List.of(Payment.class, Card.class, Premium.class, Bank.class, Holder.class),
                ModelQueryConfig.defaults()).targets(H2, POSTGRESQL, MYSQL);
    }

    /** The report lines of a check that fails, the header first. */
    private static List<String> report(PortabilityCheck check) {
        try {
            check.assertPortable();
        } catch (AssertionError e) {
            return Arrays.asList(e.getMessage().split("\n"));
        }
        throw new AssertionError("the check passed");
    }
}
