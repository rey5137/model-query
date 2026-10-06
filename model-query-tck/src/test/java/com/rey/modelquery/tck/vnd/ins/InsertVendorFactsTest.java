package com.rey.modelquery.tck.vnd.ins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.IdGeneration;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.InsertTarget;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.CompositeKeyItemEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;

/**
 * What {@code model-query-hibernate}'s {@link InsertSupport} reports of each D-116 probe root, and the built-in
 * profiles' insert values asserted against the running database (spec vendor/40 R-VND-14, vendor/41 R-PRF-11). The
 * D-116 results are why this package may name a vendor and a Hibernate version.
 */
class InsertVendorFactsTest {

    private static final InsertSupport INSERTS = new HibernateProviderSupport().inserts().orElseThrow();

    @TckTest
    void ac_vnd_11_the_insert_support_reports_each_roots_generator_and_unsupported_mappings(TckDatabase db) {
        boolean physical = db.vendor() != TckVendor.MYSQL;
        try (InsertProbes p = InsertProbes.withUnsupportedRoots(db)) {
            SessionFactory sf = p.factory();
            assertThat(INSERTS.target(sf, InsAssignedEntity.class)).isEqualTo(supported(new IdGeneration.Assigned()));
            assertThat(INSERTS.target(sf, InsIdentityEntity.class)).isEqualTo(supported(new IdGeneration.Identity()));
            assertThat(INSERTS.target(sf, InsSequenceEntity.class))
                    .isEqualTo(supported(new IdGeneration.Sequence(physical, 1)));
            assertThat(INSERTS.target(sf, InsPooledEntity.class))
                    .isEqualTo(supported(new IdGeneration.Sequence(physical, 20)));
            assertThat(INSERTS.target(sf, InsTableEntity.class)).isEqualTo(supported(new IdGeneration.Table()));
            assertThat(INSERTS.target(sf, InsUuidEntity.class)).isEqualTo(supported(new IdGeneration.Uuid()));
            assertThat(INSERTS.target(sf, InsMapsIdEntity.class).unsupportedMappings()).containsExactly("@MapsId");
            assertThat(INSERTS.target(sf, InsJoinedEntity.class).unsupportedMappings())
                    .containsExactly("JOINED inheritance");
            assertThat(INSERTS.target(sf, InsJoinedChildEntity.class).unsupportedMappings())
                    .containsExactly("JOINED inheritance");
            assertThat(INSERTS.target(sf, InsSecondaryEntity.class).unsupportedMappings())
                    .containsExactly("a @SecondaryTable");
            assertThat(INSERTS.target(sf, InsCompositeEntity.class).unsupportedMappings())
                    .containsExactly("a composite id with generated parts");
        }
        try (SessionFactory fixture = JoinTestSupport.sessionFactory(db)) {
            assertThat(INSERTS.target(fixture, CompositeKeyItemEntity.class))
                    .isEqualTo(supported(new IdGeneration.Assigned()));
        }
    }

    @TckTest
    void ac_vnd_11_the_insert_support_draws_distinct_keys_from_the_roots_generator(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            List<Object> pooled = p.inTransaction(s -> INSERTS.generateKeys(s, InsPooledEntity.class, 25));
            List<Object> table = p.inTransaction(s -> INSERTS.generateKeys(s, InsTableEntity.class, 3));
            List<Object> uuids = p.inTransaction(s -> INSERTS.generateKeys(s, InsUuidEntity.class, 3));

            assertThat(pooled).hasSize(25).doesNotContainNull().hasOnlyElementsOfType(Long.class)
                    .doesNotHaveDuplicates();
            assertThat(table).hasSize(3).doesNotContainNull().hasOnlyElementsOfType(Long.class)
                    .doesNotHaveDuplicates();
            assertThat(uuids).hasSize(3).hasOnlyElementsOfType(UUID.class).doesNotHaveDuplicates();
            assertThatThrownBy(() -> p.inTransaction(s -> INSERTS.generateKeys(s, InsIdentityEntity.class, 1)))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not generate keys before the insert");
        }
    }

    @TckTest
    void ac_vnd_11_do_nothing_is_rendered_on_hibernate_7_and_on_6_only_for_postgresql_and_mysql(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            boolean expected = InsertProbes.hibernateMajor() >= 7 || db.vendor() != TckVendor.H2;
            assertThat(INSERTS.doNothingRendered(p.factory())).isEqualTo(expected);
        }
    }

    @TckTest
    void ac_vnd_11_the_version_seed_is_the_one_bind_the_provider_adds_per_row(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertThat(INSERTS.providerBindsPerRow(p.factory(), InsAssignedEntity.class)).isEqualTo(1);
            assertThat(INSERTS.providerBindsPerRow(p.factory(), InsSourceEntity.class)).isZero();
        }
    }

    @TckTest
    void ac_prf_09_the_database_takes_a_values_insert_past_the_default_row_limit_in_one_statement(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            VendorProfile profile = profile(p.factory());
            int rows = 1_001;
            String values = IntStream.rangeClosed(1, rows).mapToObj(i -> "(" + i + ", 'v" + i + "', 'V', 0)")
                    .collect(Collectors.joining(", "));
            p.jdbc("insert into ins_assigned (id, code, name, version) values " + values);

            assertThat(profile.maxValuesRows()).isEqualTo(Integer.MAX_VALUE);
            assertThat(p.rows("select count(*) from ins_assigned")).containsExactly(String.valueOf(rows));
        }
    }

    @TckTest
    void ac_prf_09_the_conflict_target_is_honoured_where_the_profile_says_so(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            VendorProfile profile = profile(p.factory());
            p.jdbc("insert into ins_assigned (id, code, name, version) values (1, 'c1', 'N1', 0)");
            boolean honoured;
            try {
                // A conflict on the code, while the clause names the id
                p.inTransaction(s -> s.createQuery("insert into InsAssignedEntity (id, code, name) values"
                        + " (9, 'c1', 'Z9') on conflict (id) do update set name = excluded.name").executeUpdate());
                honoured = false;
            } catch (ConstraintViolationException e) {
                honoured = true;
            }

            assertThat(profile.conflictTargetHonoured()).isEqualTo(honoured);
            assertThat(p.rows("select id, code, name from ins_assigned"))
                    .containsExactly(honoured ? "1|c1|N1" : "1|c1|Z9");
        }
    }

    @TckTest
    void ac_prf_09_a_conflict_where_sees_earlier_assignments_where_the_profile_says_so(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            VendorProfile profile = profile(p.factory());
            p.jdbc("insert into ins_assigned (id, code, name, version) values (1, 'c1', 'N1', 0)");

            // The where reads both columns the update assigns: as stored it matches, and both change; after the
            // code's assignment it no longer does, so the name stays
            p.inTransaction(s -> s.createQuery("insert into InsAssignedEntity (id, code, name) values"
                    + " (1, 'c9', 'Z9') on conflict (id) do update set code = excluded.code, name = excluded.name"
                    + " where code = 'c1' and name = 'N1'").executeUpdate());

            List<String> row = p.rows("select id, code, name from ins_assigned");
            assertThat(row).containsAnyOf("1|c9|Z9", "1|c9|N1");
            assertThat(profile.conflictWhereSeesEarlierAssignments()).isEqualTo(row.equals(List.of("1|c9|N1")));
        }
    }

    private static InsertTarget supported(IdGeneration id) {
        return new InsertTarget(id, List.of());
    }

    private static VendorProfile profile(SessionFactory sf) {
        return VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile();
    }
}
