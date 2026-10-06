package com.rey.modelquery.tck.vnd.ins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.hibernate.query.IllegalQueryOperationException;

/**
 * The D-116 vendor spike, conflict clauses: the rendering and update count of Hibernate's {@code on conflict} per
 * Tier-1 vendor, on the Hibernate the build runs. Plain HQL and criteria, no insert API. The target is
 * {@code ins_assigned}, seeded with ids 1 and 2 (codes {@code c1}, {@code c2}, names {@code N1}, {@code N2}).
 */
class InsertConflictSpikeTest {

    private static final String VALUES = "insert into InsAssignedEntity (id, code, name) values ";
    private static final String ROWS = "select id, code, name, version from ins_assigned order by id";
    private static final List<String> SEEDED = List.of("1|c1|N1|0", "2|c2|N2|0");

    /** What one statement did: its count or its failure, and the SQL it ran. */
    private record Outcome(Integer count, RuntimeException failure, List<String> sql) {}

    @TckTest
    void d_116_do_nothing_skips_a_row_conflicting_on_the_named_key(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, VALUES + "(2, 'c2', 'X2'), (3, 'c3', 'N3') on conflict (id) do nothing");
            SqlSnapshots.assertMatches(db, "wrt-d116-do-nothing", o.sql());
            if (doNothingDropped(db)) {
                // Hibernate 6.6 renders a MERGE vendor's do-nothing as a plain insert, which fails on the conflict
                assertThat(o.failure()).isExactlyInstanceOf(ConstraintViolationException.class);
                assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
            } else {
                // MySQL counts the skipped row: do-nothing is "on duplicate key update id = id", with found rows
                assertThat(o.count()).isEqualTo(db.vendor() == TckVendor.MYSQL ? 2 : 1);
                assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|N2|0", "3|c3|N3|0");
            }
        }
    }

    @TckTest
    void d_116_do_nothing_on_a_natural_key_of_a_generated_root(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            p.jdbc("insert into ins_identity (code, name, version) values ('c1', 'N1', 0)");
            Outcome o = run(p, "insert into InsIdentityEntity (code, name) values ('c1', 'X'), ('c9', 'Y')"
                    + " on conflict (code) do nothing");
            if (doNothingDropped(db)) {
                assertThat(o.failure()).isExactlyInstanceOf(ConstraintViolationException.class);
                assertThat(p.rows("select code, name from ins_identity order by code")).containsExactly("c1|N1");
            } else {
                assertThat(o.count()).isEqualTo(db.vendor() == TckVendor.MYSQL ? 2 : 1);
                assertThat(p.rows("select code, name from ins_identity order by code"))
                        .containsExactly("c1|N1", "c9|Y");
            }
        }
    }

    @TckTest
    void d_116_do_update_updates_a_row_conflicting_on_the_named_key(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, VALUES + "(2, 'c2', 'X2'), (3, 'c3', 'N3') on conflict (id) do update"
                    + " set name = excluded.name");
            SqlSnapshots.assertMatches(db, "wrt-d116-do-update", o.sql());
            // MySQL counts an updated row as 2
            assertThat(o.count()).isEqualTo(db.vendor() == TckVendor.MYSQL ? 3 : 2);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|X2|0", "3|c3|N3|0");
        }
    }

    @TckTest
    void d_116_do_update_counts_a_row_it_leaves_unchanged_once(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, VALUES + "(1, 'c1', 'N1') on conflict (id) do update set name = excluded.name");
            assertThat(o.count()).isEqualTo(1);
            assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
        }
    }

    @TckTest
    void d_116_do_update_increments_the_version_only_when_it_assigns_it(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, VALUES + "(2, 'c2', 'X2') on conflict (id) do update"
                    + " set name = excluded.name, version = version + 1");
            SqlSnapshots.assertMatches(db, "wrt-d116-do-update-version", o.sql());
            assertThat(o.count()).isEqualTo(db.vendor() == TckVendor.MYSQL ? 2 : 1);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|X2|1");
        }
    }

    @TckTest
    void d_116_do_update_where_filters_the_stored_row(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, VALUES + "(1, 'c1', 'Y1'), (2, 'c2', 'Y2') on conflict (id) do update"
                    + " set name = excluded.name where name = 'N2'");
            SqlSnapshots.assertMatches(db, "wrt-d116-do-update-where", o.sql());
            // MySQL has no WHERE there: Hibernate assigns each column a CASE, so the filtered-out row counts as found
            assertThat(o.count()).isEqualTo(db.vendor() == TckVendor.MYSQL ? 3 : 1);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|Y2|0");
        }
    }

    @TckTest
    void d_116_a_conflict_on_another_unique_key_is_honoured_only_on_mysql(TckDatabase db) {
        boolean mysql = db.vendor() == TckVendor.MYSQL;
        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome skipped = run(p, VALUES + "(9, 'c1', 'Z9') on conflict (id) do nothing");
            if (mysql) {
                assertThat(skipped.count()).isEqualTo(1);
            } else {
                assertThat(skipped.failure()).isExactlyInstanceOf(ConstraintViolationException.class);
            }
            assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
            Outcome updated = run(p, VALUES + "(9, 'c1', 'Z9') on conflict (id) do update set name = excluded.name");
            if (mysql) {
                // MySQL matches any unique key, so the row with code c1 is updated though the named key is the id
                assertThat(updated.count()).isEqualTo(2);
                assertThat(p.rows(ROWS)).containsExactly("1|c1|Z9|0", "2|c2|N2|0");
            } else {
                assertThat(updated.failure()).isExactlyInstanceOf(ConstraintViolationException.class);
                assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
            }
        }
    }

    @TckTest
    void d_116_duplicate_conflict_keys_within_one_statement(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome skipped = run(p, VALUES + "(5, 'c5', 'A'), (5, 'c5', 'B') on conflict (id) do nothing");
            List<String> afterSkip = p.rows(ROWS);
            Outcome updated = run(p, VALUES + "(5, 'c5', 'A'), (5, 'c5', 'B') on conflict (id) do update"
                    + " set name = excluded.name");
            switch (db.vendor()) {
                case POSTGRESQL -> {
                    assertThat(skipped.count()).isEqualTo(1);
                    assertThat(afterSkip).contains("5|c5|A|0");
                    assertThat(updated.failure()).isExactlyInstanceOf(DataException.class)
                            .hasMessageContaining("cannot affect row a second time");
                }
                case MYSQL -> {
                    assertThat(skipped.count()).isEqualTo(2);
                    assertThat(afterSkip).contains("5|c5|A|0");
                    assertThat(updated.count()).isEqualTo(3);
                    assertThat(p.rows(ROWS)).contains("5|c5|B|0");
                }
                case H2 -> {
                    assertThat(skipped.failure()).isExactlyInstanceOf(ConstraintViolationException.class);
                    assertThat(updated.failure()).isExactlyInstanceOf(ConstraintViolationException.class);
                }
            }
        }
    }

    @TckTest
    void d_116_a_conflict_clause_on_insert_select_fails_in_the_provider(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            seed(p);
            // Hibernate cannot resolve the conflict columns of an insert-select: criteria and HQL alike
            assertThatThrownBy(() -> p.inTransaction(s -> {
                var cb = s.getCriteriaBuilder();
                var insert = cb.createCriteriaInsertSelect(InsAssignedEntity.class);
                var target = insert.getTarget();
                insert.setInsertionTargetPaths(target.get("id"), target.get("code"), target.get("name"));
                var source = cb.createTupleQuery();
                var row = source.from(InsSourceEntity.class);
                source.multiselect(row.get("id"), row.get("code"), row.get("name"));
                insert.select(source);
                insert.onConflict().conflictOnConstraintAttributes("id").onConflictDoNothing();
                return s.createMutationQuery(insert).executeUpdate();
            })).isExactlyInstanceOf(NullPointerException.class).hasMessageContaining("fromClauseIndex");
            assertThatThrownBy(() -> p.inTransaction(s -> s.createQuery("insert into InsAssignedEntity (id, code, name)"
                    + " select s.id, s.code, s.name from InsSourceEntity s on conflict (id) do nothing")
                    .executeUpdate())).isExactlyInstanceOf(NullPointerException.class);
            // with no conflict columns only PostgreSQL renders it; the others cannot emulate it for several rows
            Outcome anyKey = run(p, "insert into InsAssignedEntity (id, code, name)"
                    + " select s.id, s.code, s.name from InsSourceEntity s on conflict do nothing");
            if (db.vendor() == TckVendor.POSTGRESQL) {
                assertThat(anyKey.count()).isEqualTo(1);
            } else {
                assertThat(anyKey.failure()).isExactlyInstanceOf(IllegalStateException.class)
                        .cause().isExactlyInstanceOf(IllegalQueryOperationException.class);
            }
        }
    }

    /** Whether this Hibernate drops a do-nothing clause on this vendor: 6.6 on a {@code MERGE} vendor. */
    private static boolean doNothingDropped(TckDatabase db) {
        return db.vendor() == TckVendor.H2 && InsertProbes.hibernateMajor() < 7;
    }

    private static void seed(InsertProbes p) {
        p.jdbc("delete from ins_assigned");
        p.jdbc("insert into ins_assigned (id, code, name, version) values (1, 'c1', 'N1', 0)");
        p.jdbc("insert into ins_assigned (id, code, name, version) values (2, 'c2', 'N2', 0)");
    }

    /** Seeds the target, then runs {@code hql} in its own transaction. */
    private static Outcome run(InsertProbes p, String hql) {
        seed(p);
        try {
            int count = p.inTransaction(s -> s.createQuery(hql).executeUpdate());
            return new Outcome(count, null, p.statements());
        } catch (RuntimeException e) {
            return new Outcome(null, e, p.statements());
        }
    }
}
