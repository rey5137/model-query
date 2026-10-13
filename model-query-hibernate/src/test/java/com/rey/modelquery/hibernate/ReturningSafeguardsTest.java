package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.WriteStatement;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.List;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.tree.SqmDmlStatement;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * The Hibernate-internals safeguards of R-WRT-50 (RFC 0010 §Compatibility): per-line probed adapter selection, the
 * declared tested range with its one {@code WARN} per factory, and the first-use SQL check that refuses a translation
 * that disagrees with {@code MQ4011} rather than running SQL that may be wrong (INV-5).
 */
class ReturningSafeguardsTest {

    @Entity(name = "Probe")
    static class Probe {
        @Id
        Long id;
    }

    // ---- probe-based selection, one adapter per line

    @Test
    void ac_wrt_42_the_adapter_is_chosen_by_probing_and_exactly_one_line_serves_the_running_hibernate() {
        try (SessionFactoryImplementor sf = factory()) {
            boolean hibernate6 = new Hibernate6Mutations().probes(sf);
            boolean hibernate7 = new Hibernate7Mutations().probes(sf);
            assertThat(hibernate6 ^ hibernate7).as("exactly one line serves %s",
                    org.hibernate.Version.getVersionString()).isTrue();
            assertThat(HibernateMutations.line(sf).orElseThrow().tested())
                    .isEqualTo(hibernate6 ? Hibernate6Mutations.TESTED : Hibernate7Mutations.TESTED);
        }
    }

    // ---- the declared tested range and its one WARN per factory

    @Test
    void ac_wrt_42_the_tested_range_is_the_major_minor_line_and_warns_once_per_factory_outside_it() {
        assertThat(HibernateMutations.withinTestedRange("6.6.58.Final", "6.6.58.Final")).isTrue();
        assertThat(HibernateMutations.withinTestedRange("6.6.99.Final", "6.6.58.Final")).isTrue();
        assertThat(HibernateMutations.withinTestedRange("6.7.0.Final", "6.6.58.Final")).isFalse();
        assertThat(HibernateMutations.withinTestedRange("7.4.11.Final", "6.6.58.Final")).isFalse();
        try (SessionFactoryImplementor sf = factory(); SessionFactoryImplementor other = factory()) {
            Mutations outside = outsideLine();
            assertThat(HibernateMutations.warned(sf, outside)).as("the one WARN for this factory").isTrue();
            assertThat(HibernateMutations.warned(sf, outside)).as("not again for the same factory").isFalse();
            assertThat(HibernateMutations.warned(other, outside)).as("its own WARN").isTrue();
        }
    }

    // ---- the first-use SQL check

    @Test
    void ac_wrt_42_the_first_use_check_accepts_the_shapes_of_vendor_41_section_7() {
        assertThatCode(() -> HibernateMutations.checkSql("update Item i1_0 set name=? where i1_0.id=?",
                "update Item i1_0 set name=? where i1_0.id=? returning name,qty", 2, WriteStatement.UPDATE))
                .doesNotThrowAnyException();
        assertThatCode(() -> HibernateMutations.checkSql("delete from Item i1_0 where i1_0.id=?",
                "delete from Item i1_0 where i1_0.id=? returning name,qty", 1, WriteStatement.DELETE))
                .doesNotThrowAnyException();
        assertThatCode(() -> HibernateMutations.checkSql("update Item i1_0 set name=? where i1_0.id=?",
                "select name, qty from final table (update Item i1_0 set name=? where i1_0.id=?)", 2,
                WriteStatement.UPDATE))
                .doesNotThrowAnyException();
        assertThatCode(() -> HibernateMutations.checkSql("delete from Item i1_0 where i1_0.id=?",
                "select name, qty from old table (delete from Item i1_0 where i1_0.id=?)", 1,
                WriteStatement.DELETE))
                .doesNotThrowAnyException();
        assertThatCode(() -> HibernateMutations.checkSql("insert into Item(id,qty) values (?,?)",
                "insert into Item(id,qty) values (?,?) returning id,qty", 2, WriteStatement.INSERT))
                .doesNotThrowAnyException();
        assertThatCode(() -> HibernateMutations.checkSql("insert into Item(id,qty) values (?,?)",
                "select id, qty from final table (insert into Item(id,qty) values (?,?))", 2,
                WriteStatement.INSERT))
                .doesNotThrowAnyException();
        assertThatCode(() -> HibernateMutations.checkSql("merge into Item i1_0 using (values (?,?)) on (i1_0.id=?)",
                "select id, qty from final table (merge into Item i1_0 using (values (?,?)) on (i1_0.id=?))", 3,
                WriteStatement.INSERT))
                .doesNotThrowAnyException();
    }

    @Test
    void ac_wrt_42_the_first_use_check_refuses_a_keyword_a_placeholder_count_or_an_anchor_that_disagrees() {
        refuses("delete from Item i1_0 where i1_0.id=?",
                "delete from Item i1_0 where i1_0.id=? returning name", 1, WriteStatement.UPDATE,
                "it does not start with update");
        refuses("update Item i1_0 set name=? where i1_0.id=?", "update Item i1_0 set name=? returning name", 2,
                WriteStatement.UPDATE, "placeholders");
        refuses("update Item i1_0 set name=? where i1_0.id=?",
                "update Item i1_0 set name=? where i1_0.id=?", 2, WriteStatement.UPDATE, "returning");
        refuses("delete from Item i1_0 where i1_0.id=?",
                "delete from Item i1_0 where i1_0.id=? returning name returning qty", 1, WriteStatement.DELETE,
                "single returning");
        refuses("update Item i1_0 set name=? where i1_0.id=?",
                "select name from final table (update Item i1_0 set name=? where i1_0.id=?) returning name", 2,
                WriteStatement.UPDATE, "do not agree");
        refuses("delete from Item i1_0 where i1_0.id=?",
                "delete from Item i1_0 where i1_0.id=? returning id,qty", 1, WriteStatement.INSERT,
                "it does not start with insert or merge");
    }

    @Test
    void ac_wrt_42_the_first_use_check_ignores_a_placeholder_or_an_anchor_inside_a_string_literal() {
        assertThatCode(() -> HibernateMutations.checkSql("update Item i1_0 set name='a?b' where i1_0.id=?",
                "update Item i1_0 set name='a?b' where i1_0.id=? returning name", 1, WriteStatement.UPDATE))
                .doesNotThrowAnyException();
        String quoted = "update Item i1_0 set name=' returning ''x'' ' where i1_0.id=?";
        assertThatCode(() -> HibernateMutations.checkSql(quoted, quoted + " returning name", 1, WriteStatement.UPDATE))
                .doesNotThrowAnyException();
    }

    /** A statement that failed its first-use check is not recorded as checked, so its next use refuses again. */
    @Test
    void ac_wrt_42_a_statement_that_failed_the_first_use_check_is_refused_again_on_its_next_use() {
        String rendered = "update Item i1_0 set name=? where i1_0.id=?";
        String unanchored = "update Item i1_0 set name=? where i1_0.id=?";
        String anchored = rendered + " returning name";
        try (SessionFactoryImplementor sf = factory()) {
            for (int use = 1; use <= 2; use++) {
                assertThatThrownBy(() -> HibernateMutations.check(sf, WriteStatement.UPDATE, rendered, unanchored, 2))
                        .as("use %d", use)
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                        .hasMessageContaining("no single returning clause");
            }
            assertThatCode(() -> HibernateMutations.check(sf, WriteStatement.UPDATE, rendered, anchored, 2))
                    .doesNotThrowAnyException();
            assertThatCode(() -> HibernateMutations.check(sf, WriteStatement.DELETE, rendered, anchored, 2))
                    .as("a passed statement is not checked again").doesNotThrowAnyException();
        }
    }

    @Test
    void ac_wrt_45_the_arbiter_check_refuses_a_conflict_target_that_is_missing_or_occurs_twice() {
        String once = "insert into Item(id,qty) values (?,?) on conflict(id)";
        assertThatCode(() -> HibernateMutations.checkArbiterSql(once, once + " where qty > 0", 2,
                WriteStatement.INSERT, false)).doesNotThrowAnyException();

        String missing = "insert into Item(id,qty) values (?,?)";
        assertThatThrownBy(() -> HibernateMutations.checkArbiterSql(missing, missing + " where qty > 0", 2,
                WriteStatement.INSERT, false))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                .hasMessageContaining("not there exactly once");

        String twice = "insert into Item(id,qty) values (?,?) on conflict(id) on conflict(id)";
        assertThatThrownBy(() -> HibernateMutations.checkArbiterSql(twice, twice + " where qty > 0", 2,
                WriteStatement.INSERT, false))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                .hasMessageContaining("not there exactly once");
    }

    @Test
    void ac_wrt_45_the_arbiter_is_spliced_after_the_one_conflict_target_outside_a_string_literal() {
        assertThat(HibernateMutations.splice("insert into T(a,b) values ('on conflict (x)',?) on conflict (b) do "
                + "nothing", "a = 'it''s'")).isEqualTo("insert into T(a,b) values ('on conflict (x)',?) on conflict "
                + "(b) where a = 'it''s' do nothing");
        assertThat(HibernateMutations.splice("insert into T(a,b) values (?,?) ON CONFLICT(a,b) do update set "
                + "a=(excluded.a)", "b > 0")).isEqualTo("insert into T(a,b) values (?,?) ON CONFLICT(a,b) where b > 0 "
                + "do update set a=(excluded.a)");

        for (String unanchored : List.of("insert into T(a) values (?)",
                "insert into T(a) values (?) on conflict on constraint uq_t do update set a=(excluded.a)",
                "insert into T(a) values (?) on conflict(a) do nothing on conflict(a)")) {
            assertThatThrownBy(() -> HibernateMutations.splice(unanchored, "a > 0"))
                    .as(unanchored)
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                    .hasMessageContaining("not there exactly once");
        }
    }

    private static void refuses(String rendered, String sql, int binders, WriteStatement kind, String message) {
        assertThatThrownBy(() -> HibernateMutations.checkSql(rendered, sql, binders, kind))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                .hasMessageContaining(message);
    }

    /**
     * A stand-in adapter whose declared line is never the running one, to exercise the WARN without a second
     * Hibernate.
     */
    private static Mutations outsideLine() {
        return new Mutations() {
            @Override
            public boolean probes(SessionFactoryImplementor sf) {
                return false;
            }

            @Override
            public String tested() {
                return "1.0.0.Final";
            }

            @Override
            public Translation translate(SessionFactoryImplementor sf, SqmDmlStatement<?> dml, QueryOptions options,
                    DomainQueryExecutionContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void invalidate(SharedSessionContractImplementor session, SqmDmlStatement<?> dml) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ExecutionContext execution(DomainQueryExecutionContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static SessionFactoryImplementor factory() {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                        "jdbc:h2:mem:returning-safeguards-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        return (SessionFactoryImplementor) new Configuration().addAnnotatedClass(Probe.class)
                .buildSessionFactory(registry.build());
    }
}
