package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.inRolledBackTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.ReturningShape;
import com.rey.modelquery.jpa.spi.WriteStatement;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.KeysetTypeEntity;
import com.rey.modelquery.tck.col.OrderArchiveEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.ins.InsArbiterEntity;
import com.rey.modelquery.tck.vnd.ins.InsAssignedEntity;
import com.rey.modelquery.tck.vnd.ins.InsIdentityEntity;
import com.rey.modelquery.tck.vnd.ins.InsKeyedEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Assumptions;

/**
 * Insert-values with a conflict clause on every Tier-1 vendor: {@code doNothing} and {@code doUpdate} with
 * {@code setFromRow}, {@code set}, {@code setNull}, {@code where} and {@code keepVersion}, the count each vendor
 * returns, the first-execution refusals ({@code MQ1804}) and rows per statement counting the clause's binds (spec
 * api/14 R-WRT-29, R-WRT-34, R-WRT-35, R-WRT-36, D-116, D-117). The probe target is {@code ins_assigned}, seeded with
 * ids 1 and 2 (codes {@code c1}, {@code c2}, names {@code N1}, {@code N2}, version 0).
 */
class InsertConflictTest {

    /** An {@code ins_assigned} row. */
    record Named(Long id, String code, String name) {}

    /** An {@code ins_keyed} row. */
    record Keyed(Long id, String code, String region, String ref, String name, Long owner) {}

    /** An {@code ins_identity} row; the root generates its id. */
    record Coded(String code, String name) {}

    /** An {@code ins_keyed} row's converted shape. */
    record Shaped(Long id, KeysetTypeEntity.Shape shape) {}

    /** An archive row with its customer by id. */
    record Archived(Long id, Long orderId, Long customer, String status) {}

    /** A {@code keyset_types} row: a JDBC-typed UUID and a JPA-converted value class among its columns. */
    record TypedRow(Long id, Integer tie, BigDecimal amount, Timestamp stamp, UUID token, byte[] payload,
            KeysetTypeEntity.Shape shape) {}

    private static final String ROWS = "select id, code, name, version from ins_assigned order by id";
    private static final List<String> SEEDED = List.of("1|c1|N1|0", "2|c2|N2|0");

    private static final TableField<InsAssignedEntity, InsAssignedEntity> ASSIGNED =
            TableField.root(InsAssignedEntity.class);
    private static final ColumnField<Named, InsAssignedEntity, Long> ID =
            ColumnField.of(Named.class, ASSIGNED, "id", Long.class);
    private static final ColumnField<Named, InsAssignedEntity, String> CODE =
            ColumnField.of(Named.class, ASSIGNED, "code", String.class);
    private static final ColumnField<Named, InsAssignedEntity, String> NAME =
            ColumnField.of(Named.class, ASSIGNED, "name", String.class);
    /** The {@code @Version} column, which no model holds. */
    private static final ColumnField<InsAssignedEntity, InsAssignedEntity, Integer> VERSION =
            ColumnField.of(InsAssignedEntity.class, ASSIGNED, "version", Integer.class);
    /** The {@code @Version} column as a conflict update's {@code where} reads it. */
    private static final ColumnField<Named, InsAssignedEntity, Integer> STORED_VERSION =
            ColumnField.of(Named.class, ASSIGNED, "version", Integer.class);
    private static final InsertColumns<Named, InsAssignedEntity> NAMED =
            InsertColumns.<Named, InsAssignedEntity>of(ASSIGNED).addKey(ID, Named::id).add(CODE, Named::code)
                    .add(NAME, Named::name);

    /** What one insert did: its count or its failure, and the inserts or merges it ran. */
    private record Outcome(Long count, RuntimeException failure, List<String> sql) {}

    // ---- AC-WRT-25

    @TckTest
    void ac_wrt_25_do_nothing_skips_a_conflicting_row_counting_as_the_vendor_does(TckDatabase db) {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(2L, "c2", "X2"),
                new Named(3L, "c3", "N3"))).onConflict(ID).doNothing().anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, insert);

            if (!new HibernateProviderSupport().inserts().orElseThrow().doNothingRendered(p.factory())) {
                // Hibernate 6.6 renders a MERGE vendor's doNothing as a plain insert, so it is refused first
                assertRefused(o, "does not render doNothing()");
                assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
                return;
            }
            // MySQL counts the row it skipped: doNothing is a self-assignment, with found rows
            assertThat(o.count()).isEqualTo(anyKey(p.factory()) ? 2 : 1);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|N2|0", "3|c3|N3|0");
            SqlSnapshots.assertMatches(db, "wrt-25-do-nothing", o.sql());
        }
    }

    @TckTest
    void ac_wrt_25_do_update_from_the_row_where_the_stored_row_matches_increments_the_version(TckDatabase db) {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(1L, "c1", "Y1"),
                        new Named(2L, "c2", "Y2"), new Named(3L, "c3", "N3")))
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.eq(NAME, "N2"))).anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, insert);

            // Row 1 is filtered out, row 2 updated, row 3 inserted. MySQL: 1 for the row left unchanged, 2 for the
            // changed one, 1 for the new one
            assertThat(o.failure()).isNull();
            assertThat(o.count()).isEqualTo(anyKey(p.factory()) ? 4 : 2);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|Y2|1", "3|c3|N3|0");
            SqlSnapshots.assertMatches(db, "wrt-25-do-update-where", o.sql());
        }
    }

    @TckTest
    void ac_wrt_25_under_inline_value_handling_a_conflict_where_value_renders_as_before(TckDatabase db) {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(1L, "c1", "Y1"),
                        new Named(2L, "c2", "Y2"), new Named(3L, "c3", "N3")))
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.eq(NAME, "N2"))).anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.withInlineValues(db)) {
            Outcome o = run(p, insert);

            // As under the default setting: no executor binds a parameter in the conflict clause, so its value is not
            // bound explicitly (no MQ1804) and the factory inlines it, as before R-FLT-08 (M30.6 binds it)
            assertThat(o.failure()).isNull();
            assertThat(o.count()).isEqualTo(anyKey(p.factory()) ? 4 : 2);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|Y2|1", "3|c3|N3|0");
            assertThat(o.sql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("'N2'"));
        }
    }

    @TckTest
    void ac_wrt_25_a_where_reading_two_assigned_columns_throws_mq1804_by_default_on_every_vendor(TckDatabase db) {
        var codeAndName = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(2L, "c2x", "X2")))
                .onConflict(ID).doUpdate(u -> u.setFromRow(CODE, NAME).where(f -> f.eq(CODE, "c2").eq(NAME, "N2")))
                .anyUniqueKey().build();
        // the version increment is an assignment the where reads, unless keepVersion()
        var nameAndVersion = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(2L, "c2", "X2")))
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.eq(NAME, "N2").eq(STORED_VERSION, 0)))
                .anyUniqueKey().build();
        var kept = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(2L, "c2", "X2")))
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.eq(NAME, "N2").eq(STORED_VERSION, 0)))
                .keepVersion().anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            assertRefused(run(p, codeAndName), "reads [code, name], which the update also assigns; on some");
            assertRefused(run(p, nameAndVersion), "reads [name, version], which the update also assigns; on some");
            assertThat(p.rows(ROWS)).isEqualTo(SEEDED);

            // with keepVersion() the where reads one assigned column, which runs on every vendor
            Outcome o = run(p, kept);
            assertThat(o.failure()).isNull();
            assertThat(o.count()).isEqualTo(anyKey(p.factory()) ? 2 : 1);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|X2|0");
        }
    }

    @TckTest
    void ac_wrt_25_with_the_option_a_where_reading_two_assigned_columns_reads_the_stored_row_or_throws_mq1804(
            TckDatabase db) {
        var config = ModelQueryConfig.defaults().conflictUpdateWhereOnAssignedColumns(true);
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(1L, "c1x", "Y1"),
                        new Named(2L, "c2x", "X2"), new Named(3L, "c3", "N3")))
                .onConflict(ID).doUpdate(u -> u.setFromRow(CODE, NAME).where(f -> f.eq(CODE, "c2").eq(NAME, "N2")))
                .anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, insert, config);

            if (seesEarlierAssignments(p.factory())) {
                // MySQL: the name's CASE would read the code the first assignment wrote, and leave the name alone
                assertRefused(o, "evaluates it per assignment");
                assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
                return;
            }
            // Row 1 is filtered out, row 2 matches as stored and both its columns change, row 3 is inserted
            assertThat(o.failure()).isNull();
            assertThat(o.count()).isEqualTo(anyKey(p.factory()) ? 4 : 2);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2x|X2|1", "3|c3|N3|0");
            SqlSnapshots.assertMatches(db, "wrt-25-do-update-where-on-assigned-columns", o.sql());
        }
    }

    @TckTest
    void ac_wrt_25_do_update_sets_a_value_or_null_on_a_unique_column_conflict_and_keep_version_keeps_it(
            TckDatabase db) {
        List<Named> rows = List.of(new Named(7L, "c1", "A"), new Named(8L, "c8", "B"));
        var kept = ValuesInsert.builder(NAMED, Long.class, rows)
                .onConflict(CODE).doUpdate(u -> u.set(NAME, "Z")).keepVersion().anyUniqueKey().build();
        var nulled = ValuesInsert.builder(NAMED, Long.class, rows.subList(0, 1))
                .onConflict(CODE).doUpdate(u -> u.setNull(NAME)).anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome set = run(p, kept);
            assertThat(set.failure()).isNull();
            // the stored row with code c1 changes, row 8 is new: MySQL counts the change as 2
            assertThat(set.count()).isEqualTo(anyKey(p.factory()) ? 3 : 2);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|Z|0", "2|c2|N2|0", "8|c8|B|0");
            SqlSnapshots.assertMatches(db, "wrt-25-do-update-set-keep-version", set.sql());

            Outcome setNull = run(p, nulled);
            assertThat(setNull.failure()).isNull();
            assertThat(setNull.count()).isEqualTo(anyKey(p.factory()) ? 2 : 1);
            assertThat(p.rows(ROWS)).containsExactly("1|c1|null|1", "2|c2|N2|0");
        }
    }

    @TckTest
    void ac_wrt_25_mysql_without_any_unique_key_throws_mq1804_before_any_statement(TckDatabase db) {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(2L, "c2", "X2"),
                new Named(3L, "c3", "N3"))).onConflict(ID).doUpdate(u -> u.setFromRow(NAME)).build();

        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, insert);

            if (anyKey(p.factory())) {
                assertRefused(o, "detects a conflict on any unique key");
                assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
            } else {
                // the named key is honoured: inserted plus updated
                assertThat(o.count()).isEqualTo(2);
                assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|X2|1", "3|c3|N3|0");
            }
        }
    }

    @TckTest
    void ac_wrt_25_do_nothing_the_provider_does_not_render_throws_mq1804_on_first_execution(TckDatabase db) {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(3L, "c3", "N3")))
                .onConflict(ID).doNothing().anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            NoTablesProviderSupport.serving(UnrenderedDoNothingProviderSupport.class,
                    () -> assertRefused(run(p, insert), "does not render doNothing()"));
            assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
        }
    }

    @TckTest
    void ac_wrt_25_a_conflict_update_binds_its_values_through_their_attribute_mapping(TckDatabase db) {
        TableField<KeysetTypeEntity, KeysetTypeEntity> types = TableField.root(KeysetTypeEntity.class);
        var id = ColumnField.of(TypedRow.class, types, "id", Long.class);
        var token = ColumnField.of(TypedRow.class, types, "token", UUID.class);
        var shape = ColumnField.of(TypedRow.class, types, "shape", KeysetTypeEntity.Shape.class);
        var columns = InsertColumns.<TypedRow, KeysetTypeEntity>of(types)
                .addKey(id, TypedRow::id)
                .add(ColumnField.of(TypedRow.class, types, "tie", Integer.class), TypedRow::tie)
                .add(ColumnField.of(TypedRow.class, types, "amount", BigDecimal.class), TypedRow::amount)
                .add(ColumnField.of(TypedRow.class, types, "stamp", Timestamp.class), TypedRow::stamp)
                .add(token, TypedRow::token)
                .add(ColumnField.of(TypedRow.class, types, "payload", byte[].class), TypedRow::payload)
                .add(shape, TypedRow::shape);
        UUID updated = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
        // Row 1 is in the fixture.
        var insert = ValuesInsert.builder(columns, Long.class, List.of(new TypedRow(1L, 1, BigDecimal.ONE,
                        new Timestamp(0), new UUID(0, 99), new byte[] {1}, new KeysetTypeEntity.Shape("circle"))))
                .onConflict(id).doUpdate(u -> u.set(token, updated).set(shape, new KeysetTypeEntity.Shape("hexagon")))
                .anyUniqueKey().build();
        var stored = new ArrayList<String>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            assertThat(ModelQueryExecutor.create(em, KeysetTypeEntity.class, ModelQueryConfig.defaults())
                    .insert(insert)).isEqualTo(anyKey(em.getEntityManagerFactory()) ? 2 : 1);
            Object[] row = (Object[]) em.createNativeQuery("select token, shape from keyset_types where id = 1")
                    .getSingleResult();
            stored.add(row[0] + "|" + row[1]);
        });

        // Bound as the attribute's type: the UUID as its VARCHAR text, the Shape through its AttributeConverter.
        assertThat(stored).containsExactly(updated + "|hexagon");
    }

    @TckTest
    void ac_wrt_25_a_conflict_update_sets_null_on_a_converted_attribute(TckDatabase db) {
        TableField<InsKeyedEntity, InsKeyedEntity> keyed = TableField.root(InsKeyedEntity.class);
        var id = ColumnField.of(Shaped.class, keyed, "id", Long.class);
        var shape = ColumnField.of(Shaped.class, keyed, "shape", KeysetTypeEntity.Shape.class);
        var columns = InsertColumns.<Shaped, InsKeyedEntity>of(keyed).addKey(id, Shaped::id).add(shape, Shaped::shape);
        var insert = ValuesInsert.builder(columns, Long.class, List.of(new Shaped(1L, new KeysetTypeEntity.Shape("x"))))
                .onConflict(id).doUpdate(u -> u.setNull(shape)).anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            p.jdbc("insert into ins_keyed (id, shape) values (1, 'circle')");
            long count = p.factory().fromTransaction(session ->
                    ModelQueryExecutor.create(session, InsKeyedEntity.class, ModelQueryConfig.defaults())
                            .insert(insert));

            // A NULL bound as the converted attribute's type: a NULL literal typed by Shape fails, Shape being no
            // type the provider knows
            assertThat(count).isEqualTo(anyKey(p.factory()) ? 2 : 1);
            assertThat(p.rows("select id, shape from ins_keyed")).containsExactly("1|null");
        }
    }

    @TckTest
    void ac_wrt_25_a_conflict_update_writes_a_to_one_by_id_from_the_row_or_a_value(TckDatabase db) {
        TableField<OrderArchiveEntity, OrderArchiveEntity> archive = TableField.root(OrderArchiveEntity.class);
        var id = ColumnField.of(Archived.class, archive, "id", Long.class);
        var customer = ColumnField.of(Archived.class, archive, "customer", Long.class);
        var status = ColumnField.of(Archived.class, archive, "status", String.class);
        var columns = InsertColumns.<Archived, OrderArchiveEntity>of(archive).addKey(id, Archived::id)
                .add(ColumnField.of(Archived.class, archive, "orderId", Long.class), Archived::orderId)
                .add(customer, Archived::customer).add(status, Archived::status);
        var stored = new ArrayList<String>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            var executor = ModelQueryExecutor.create(em, OrderArchiveEntity.class, ModelQueryConfig.defaults());
            executor.insert(ValuesInsert.builder(columns, Long.class, List.of(new Archived(9001L, 42L, 3L, "PAID")))
                    .build());
            List<Archived> again = List.of(new Archived(9001L, 42L, 7L, "NEW"));
            executor.insert(ValuesInsert.builder(columns, Long.class, again).onConflict(id)
                    .doUpdate(u -> u.setFromRow(customer, status)).anyUniqueKey().build());
            stored.add(archived(em));
            executor.insert(ValuesInsert.builder(columns, Long.class, again).onConflict(id)
                    .doUpdate(u -> u.set(customer, 3L)).anyUniqueKey().build());
            stored.add(archived(em));
        });

        // id|customer|status|version: the row's customer 7, then the value 3, each update incrementing the version
        assertThat(stored).containsExactly("9001|7|NEW|1", "9001|3|NEW|2");
    }

    // ---- AC-WRT-32

    @TckTest
    void ac_wrt_32_a_conflict_key_that_is_no_declared_unique_key_throws_mq1804_on_first_execution(TckDatabase db) {
        TableField<InsKeyedEntity, InsKeyedEntity> keyed = TableField.root(InsKeyedEntity.class);
        var id = ColumnField.of(Keyed.class, keyed, "id", Long.class);
        var code = ColumnField.of(Keyed.class, keyed, "code", String.class);
        var region = ColumnField.of(Keyed.class, keyed, "region", String.class);
        var ref = ColumnField.of(Keyed.class, keyed, "ref", String.class);
        var name = ColumnField.of(Keyed.class, keyed, "name", String.class);
        var owner = ColumnField.of(Keyed.class, keyed, "owner", Long.class);
        var columns = InsertColumns.<Keyed, InsKeyedEntity>of(keyed).addKey(id, Keyed::id).add(code, Keyed::code)
                .add(region, Keyed::region).add(ref, Keyed::ref).add(name, Keyed::name).add(owner, Keyed::owner);
        List<Keyed> rows = List.of(new Keyed(1L, "k1", "EU", "r1", "N1", null));

        try (InsertProbes p = InsertProbes.open(db)) {
            // the id, the @NaturalId, the @Table unique constraint, its ref_no column renamed, and the to-one's
            // unique join column
            for (var key : List.<List<ColumnField<Keyed, InsKeyedEntity, ?>>>of(List.of(id), List.of(code),
                    List.of(region, ref), List.of(ref, region), List.of(owner))) {
                var updated = key.contains(name) ? code : name;
                var insert = ValuesInsert.builder(columns, Long.class, rows).onConflict(key.get(0), rest(key))
                        .doUpdate(u -> u.setFromRow(updated)).anyUniqueKey().build();
                assertThat(inRolledBack(p, InsKeyedEntity.class, insert)).as(key.toString()).isEqualTo(1);
            }
            // a column unique nowhere, part of a constraint, or a superset of a key
            for (var key : List.<List<ColumnField<Keyed, InsKeyedEntity, ?>>>of(List.of(name), List.of(region),
                    List.of(id, code))) {
                var updated = key.contains(name) ? code : name;
                var insert = ValuesInsert.builder(columns, Long.class, rows).onConflict(key.get(0), rest(key))
                        .doUpdate(u -> u.setFromRow(updated)).anyUniqueKey().build();
                assertThatThrownBy(() -> inRolledBack(p, InsKeyedEntity.class, insert)).as(key.toString())
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
                            assertThat(e.code()).isEqualTo(MqCode.MQ1804);
                            assertThat(e.getMessage()).contains("names no unique key of the mapping");
                        });
            }
            assertThat(p.rows("select id from ins_keyed")).isEmpty();
        }
    }

    @TckTest
    void ac_wrt_32_a_conflict_update_assigning_the_version_or_the_id_throws_mq1804_on_first_execution(
            TckDatabase db) {
        var version = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(3L, "c3", "N3")))
                .onConflict(ID).doUpdate(u -> u.set(VERSION, 5)).anyUniqueKey().build();
        TableField<InsIdentityEntity, InsIdentityEntity> identity = TableField.root(InsIdentityEntity.class);
        var code = ColumnField.of(Coded.class, identity, "code", String.class);
        var generatedId = ColumnField.of(InsIdentityEntity.class, identity, "id", Long.class);
        var id = ValuesInsert.builder(InsertColumns.<Coded, InsIdentityEntity>of(identity).add(code, Coded::code)
                        .add(ColumnField.of(Coded.class, identity, "name", String.class), Coded::name), Long.class,
                List.of(new Coded("c3", "N3")))
                .onConflict(code).doUpdate(u -> u.set(generatedId, 5L)).anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            assertRefused(run(p, version), "the @Version attribute");
            assertThatThrownBy(() -> inRolledBack(p, InsIdentityEntity.class, id))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
                        assertThat(e.code()).isEqualTo(MqCode.MQ1804);
                        assertThat(e.getMessage()).contains("an id attribute");
                    });
            assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
        }
    }

    @TckTest
    void ac_wrt_25_a_bound_argument_in_a_conflict_update_where_throws_mq1804_on_first_execution(TckDatabase db) {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(3L, "c3", "N3")))
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.compare(NAME, Op.NE, Expr.value("x"))))
                .anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            assertRefused(run(p, insert), "Expr.value");
        }
    }

    // ---- AC-FLT-26: an op in a conflict clause (R-WRT-34, R-WRT-52)

    private static final FilterOperator<String, String> DIFFERS =
            FilterOperator.of("differs", (ctx, left, right) -> ctx.cb().notEqual(left, right));
    private static final FilterOperator<String, String> SAME =
            FilterOperator.of("same", (ctx, left, right) -> ctx.cb().equal(left, right));

    @TckTest
    void ac_flt_26_a_value_form_op_in_a_conflict_update_where_is_mq1804_naming_it_on_first_execution(TckDatabase db) {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(3L, "c3", "N3")))
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.op(DIFFERS, NAME, "x")))
                .anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            assertRefused(run(p, insert), "doUpdate(...).where(...) with op(differs)");
            assertThat(p.rows(ROWS)).isEqualTo(SEEDED);
        }
    }

    @TckTest
    void ac_flt_26_a_field_form_op_in_a_conflict_update_where_runs(TckDatabase db) {
        var rows = List.of(new Named(1L, "c1", "Y1"), new Named(2L, "c2", "Y2"), new Named(3L, "c3", "N3"));
        var differing = ValuesInsert.builder(NAMED, Long.class, rows)
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.op(DIFFERS, NAME, CODE)))
                .anyUniqueKey().build();
        var same = ValuesInsert.builder(NAMED, Long.class, rows)
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.op(SAME, NAME, CODE)))
                .anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, differing);
            assertThat(o.failure()).isNull();
            // Every stored name differs from its code, so both conflicting rows update and row 3 inserts.
            assertThat(p.rows(ROWS)).containsExactly("1|c1|Y1|1", "2|c2|Y2|1", "3|c3|N3|0");

            o = run(p, same);
            assertThat(o.failure()).isNull();
            // No stored name equals its code: the conflicting rows stay as seeded.
            assertThat(p.rows(ROWS)).containsExactly("1|c1|N1|0", "2|c2|N2|0", "3|c3|N3|0");
        }
    }

    @TckTest
    void ac_flt_26_an_op_in_an_arbiter_where_is_mq1804_naming_it_at_build(TckDatabase db) {
        var rows = List.of(new ArbiterRow(3L, "k3", "s1", "OPEN", "X1"));
        assertThatThrownBy(() -> ValuesInsert.builder(ARBITER_COLUMNS, Long.class, rows)
                .onConflict(ARBITER_SKU).where(f -> f.op(DIFFERS, ARBITER_STATUS, "CLOSED"))
                .doUpdate(u -> u.setFromRow(ARBITER_NAME)).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1804))
                .hasMessageContaining("onConflict(...).where(...) with op(differs)");
        assertThatThrownBy(() -> ValuesInsert.builder(ARBITER_COLUMNS, Long.class, rows)
                .onConflict(ARBITER_SKU).where(f -> f.op(DIFFERS, ARBITER_STATUS, ARBITER_NAME))
                .doUpdate(u -> u.setFromRow(ARBITER_NAME)).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1804))
                .hasMessageContaining("op(differs)");
    }

    // ---- AC-WRT-24

    @TckTest
    void ac_wrt_24_rows_per_statement_count_the_conflict_clause_binds(TckDatabase db) {
        // Three columns and the version seed: 4 binds a row. The clause: its where's 1 bind, once and once per
        // assignment (the row's name and the version), plus the version increment: 4. So 14 binds take 2 rows, not 3
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(InsertValuesTest.limited(db, 14,
                Integer.MAX_VALUE)));
        var rows = LongStream.rangeClosed(3, 7).mapToObj(i -> new Named(i, "c" + i, "N" + i)).toList();
        var insert = ValuesInsert.builder(NAMED, Long.class, rows)
                .onConflict(ID).doUpdate(u -> u.setFromRow(NAME).where(f -> f.ne(NAME, "x"))).anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, insert, config);

            assertThat(o.failure()).isNull();
            assertThat(o.count()).isEqualTo(5);
            assertThat(o.sql()).hasSize(3).allSatisfy(statement -> assertThat(BulkWriteTest.binds(statement))
                    .isLessThanOrEqualTo(14));
        }
    }

    // ---- AC-WRT-44

    /**
     * A conflict {@code setExpression} renders before the clause's other assignments (R-WRT-13) and reads both the
     * stored row and the incoming row: {@code name} is written from the stored {@code N1} and the incoming
     * {@code IN} although {@code code}, assigned first in the builder, renders after it (R-WRT-51).
     */
    @TckTest
    void ac_wrt_44_a_conflict_set_expression_renders_before_the_other_assignments(TckDatabase db) {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(1L, "c1", "IN")))
                .onConflict(ID).doUpdate(u -> u.set(CODE, "x")
                        .setExpression(NAME, (stored, incoming, cb) -> cb.concat(stored, incoming)))
                .anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            Outcome o = run(p, insert);

            assertThat(o.failure()).isNull();
            assertThat(p.rows(ROWS)).containsExactly("1|x|N1IN|1", "2|c2|N2|0");
            SqlSnapshots.assertMatches(db, "wrt-44-conflict-set-expression", inserts(p));
        }
    }

    // ---- AC-WRT-45

    /** An {@code ins_arbiter} row: its {@code sku} is unique only while the row is open (R-WRT-52). */
    record ArbiterRow(Long id, String code, String sku, String status, String name) {}

    private static final String ARBITER_ROWS = "select id, code, sku, status, name from ins_arbiter order by id";
    private static final List<String> ARBITER_SEEDED = List.of("1|k1|s1|OPEN|N1", "2|k2|s2|CLOSED|N2");

    /** The partial unique index PostgreSQL infers from {@code on conflict (sku) where status <> 'CLOSED'}. */
    private static final String PARTIAL_INDEX =
            "create unique index ins_arbiter_open_sku on ins_arbiter (sku) where status <> 'CLOSED'";

    private static final TableField<InsArbiterEntity, InsArbiterEntity> ARBITER =
            TableField.root(InsArbiterEntity.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, Long> ARBITER_ID =
            ColumnField.of(ArbiterRow.class, ARBITER, "id", Long.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, String> ARBITER_CODE =
            ColumnField.of(ArbiterRow.class, ARBITER, "code", String.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, String> ARBITER_SKU =
            ColumnField.of(ArbiterRow.class, ARBITER, "sku", String.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, String> ARBITER_STATUS =
            ColumnField.of(ArbiterRow.class, ARBITER, "status", String.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, String> ARBITER_NAME =
            ColumnField.of(ArbiterRow.class, ARBITER, "name", String.class);
    private static final InsertColumns<ArbiterRow, InsArbiterEntity> ARBITER_COLUMNS =
            InsertColumns.<ArbiterRow, InsArbiterEntity>of(ARBITER).addKey(ARBITER_ID, ArbiterRow::id)
                    .add(ARBITER_CODE, ArbiterRow::code).add(ARBITER_SKU, ArbiterRow::sku)
                    .add(ARBITER_STATUS, ArbiterRow::status).add(ARBITER_NAME, ArbiterRow::name);
    /** What an arbiter upsert maps the written row to (R-WRT-50). */
    private static final ModelQuery.Builder<InsArbiterEntity, Long, ArbiterRow> ARBITER_RETURNING =
            ModelQuery.builder(ARBITER, row -> new ArbiterRow(row.get(ARBITER_ID), row.get(ARBITER_CODE),
                            row.get(ARBITER_SKU), row.get(ARBITER_STATUS), row.get(ARBITER_NAME)))
                    .select(SelectSet.of(ARBITER_ID, ARBITER_CODE, ARBITER_SKU, ARBITER_STATUS, ARBITER_NAME))
                    .primaryKey(PrimaryKey.of(ARBITER_ID));

    /**
     * An arbiter predicate on the conflict target is rendered with the statement (R-WRT-52): PostgreSQL infers the
     * partial unique index {@code (sku) where status <> 'CLOSED'}, so the row whose sku is open is updated, while a
     * new row whose sku matches only a closed row inserts, the closed row being outside the index. H2 and MySQL, whose
     * profile does not honour the arbiter, refuse {@code MQ1804} on first execution, before any statement.
     */
    @TckTest
    void ac_wrt_45_a_conflict_arbiter_updates_the_open_row_and_inserts_past_the_closed_one(TckDatabase db) {
        var insert = ValuesInsert.builder(ARBITER_COLUMNS, Long.class, List.of(
                        new ArbiterRow(3L, "k3", "s1", "OPEN", "X1"),
                        new ArbiterRow(4L, "k4", "s2", "OPEN", "X2")))
                .onConflict(ARBITER_SKU).where(f -> f.ne(ARBITER_STATUS, "CLOSED"))
                .doUpdate(u -> u.setFromRow(ARBITER_NAME)).build();

        try (InsertProbes p = InsertProbes.withArbiterRoot(db)) {
            if (!arbiterHonoured(p.factory())) {
                assertRefused(runArbiter(p, insert, false), "takes no arbiter predicate");
                assertThat(p.rows(ARBITER_ROWS)).isEqualTo(ARBITER_SEEDED);
                return;
            }
            Outcome o = runArbiter(p, insert, true);

            assertThat(o.failure()).isNull();
            assertThat(o.count()).isEqualTo(2);
            assertThat(p.rows(ARBITER_ROWS)).containsExactly("1|k1|s1|OPEN|X1", "2|k2|s2|CLOSED|N2",
                    "4|k4|s2|OPEN|X2");
            SqlSnapshots.assertMatches(db, "wrt-45-arbiter", o.sql());
        }
    }

    /**
     * {@code onConstraint(name)} names a unique constraint of the table rather than its key columns (R-WRT-52):
     * PostgreSQL takes it, and {@code doNothing()} skips the row that conflicts on the named constraint. H2 and MySQL,
     * whose profile does not honour it, refuse {@code MQ1804} on first execution, before any statement.
     */
    @TckTest
    void ac_wrt_45_on_constraint_do_nothing_skips_the_row_conflicting_on_the_named_constraint(TckDatabase db) {
        var insert = ValuesInsert.builder(ARBITER_COLUMNS, Long.class, List.of(
                        new ArbiterRow(3L, "k1", "sX", "OPEN", "X1"),
                        new ArbiterRow(4L, "k4", "s4", "OPEN", "X2")))
                .onConstraint("ins_arbiter_code_key").doNothing().build();

        try (InsertProbes p = InsertProbes.withArbiterRoot(db)) {
            if (!constraintHonoured(p.factory())) {
                assertRefused(runArbiter(p, insert, false), "takes no onConstraint");
                assertThat(p.rows(ARBITER_ROWS)).isEqualTo(ARBITER_SEEDED);
                return;
            }
            Outcome o = runArbiter(p, insert, false);

            assertThat(o.failure()).isNull();
            assertThat(p.rows(ARBITER_ROWS)).containsExactly("1|k1|s1|OPEN|N1", "2|k2|s2|CLOSED|N2",
                    "4|k4|s4|OPEN|X2");
            SqlSnapshots.assertMatches(db, "wrt-45-on-constraint", o.sql());
        }
    }

    /**
     * An {@code InsertSupport} written before {@code arbiterRendered}, delegating the rest to Hibernate's, answers its
     * default, false: an arbiter upsert is {@code MQ4011} before the flush and any statement (R-WRT-52, INV-5, D-132).
     */
    @TckTest
    void ac_wrt_45_an_insert_support_rendering_no_arbiter_refuses_it_with_mq4011_before_any_statement(TckDatabase db) {
        var insert = ValuesInsert.builder(ARBITER_COLUMNS, Long.class,
                        List.of(new ArbiterRow(3L, "k3", "s1", "OPEN", "X1")))
                .onConflict(ARBITER_SKU).where(f -> f.ne(ARBITER_STATUS, "CLOSED"))
                .doUpdate(u -> u.setFromRow(ARBITER_NAME)).build();

        try (InsertProbes p = InsertProbes.withArbiterRoot(db)) {
            List<Outcome> outcomes = new ArrayList<>();
            // The factory resolves its provider support once, so the profile is asked inside, as the stub serves it
            NoTablesProviderSupport.serving(UnrenderedDoNothingProviderSupport.class, () -> {
                Assumptions.assumeTrue(arbiterHonoured(p.factory()), "the vendor takes no conflict arbiter (MQ1804)");
                outcomes.add(runArbiter(p, insert, true));
            });

            assertThat(outcomes.get(0).failure()).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                    .hasMessageContaining("renders no arbiter predicate on a conflict target");
            assertThat(p.statements()).as("no statement ran").isEmpty();
            assertThat(p.rows(ARBITER_ROWS)).isEqualTo(ARBITER_SEEDED);
        }
    }

    /**
     * An arbiter upsert returns the row it updated on PostgreSQL, the arbiter predicate and the returning clause
     * composing into one statement (R-WRT-50, R-WRT-52).
     */
    @TckTest
    void ac_wrt_45_a_returning_insert_with_an_arbiter_returns_the_row_it_updated(TckDatabase db) {
        var insert = ValuesInsert.builder(ARBITER_COLUMNS, Long.class,
                        List.of(new ArbiterRow(3L, "k3", "s1", "OPEN", "X1")))
                .onConflict(ARBITER_SKU).where(f -> f.ne(ARBITER_STATUS, "CLOSED"))
                .doUpdate(u -> u.setFromRow(ARBITER_NAME)).build();
        var returned = new ArrayList<ArbiterRow>();

        try (InsertProbes p = InsertProbes.withArbiterRoot(db)) {
            Assumptions.assumeTrue(arbiterHonoured(p.factory()), "the vendor takes no conflict arbiter (MQ1804)");
            Assumptions.assumeTrue(returningShape(db) != null, "the database returns no rows of a write (MQ4011)");
            seedArbiter(p, true);
            returned.addAll(p.factory().fromTransaction(session -> ModelQueryExecutor
                    .create(session, InsArbiterEntity.class, ModelQueryConfig.defaults())
                    .insertReturning(insert, ARBITER_RETURNING.build())));

            assertThat(returned).containsExactly(new ArbiterRow(1L, "k1", "s1", "OPEN", "X1"));
            SqlSnapshots.assertMatches(db, "wrt-45-returning", inserts(p));
        }
    }

    // ---- helpers

    /** Seeds {@code ins_arbiter} and, where {@code partialIndex}, creates the index the arbiter infers (R-WRT-52). */
    private static void seedArbiter(InsertProbes p, boolean partialIndex) {
        p.jdbc("delete from ins_arbiter");
        p.jdbc("insert into ins_arbiter (id, code, sku, status, name) values (1, 'k1', 's1', 'OPEN', 'N1')");
        p.jdbc("insert into ins_arbiter (id, code, sku, status, name) values (2, 'k2', 's2', 'CLOSED', 'N2')");
        if (partialIndex) {
            p.jdbc(PARTIAL_INDEX);
        }
        p.forget();
    }

    /** Seeds the target, then runs {@code insert} in its own transaction. */
    private static Outcome runArbiter(InsertProbes p, ModelInsert<InsArbiterEntity, ?> insert,
            boolean partialIndex) {
        seedArbiter(p, partialIndex);
        try {
            long count = p.factory().fromTransaction(session ->
                    ModelQueryExecutor.create(session, InsArbiterEntity.class, ModelQueryConfig.defaults())
                            .insert(insert));
            return new Outcome(count, null, inserts(p));
        } catch (RuntimeException e) {
            return new Outcome(null, e, inserts(p));
        }
    }

    /** Whether {@code emf}'s built-in profile reports a vendor that renders a conflict arbiter (R-WRT-52). */
    private static boolean arbiterHonoured(EntityManagerFactory emf) {
        return VendorResolver.resolve(emf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile()
                .conflictArbiterPredicate();
    }

    /** Whether {@code emf}'s built-in profile reports a vendor that takes {@code onConstraint(name)} (R-WRT-52). */
    private static boolean constraintHonoured(EntityManagerFactory emf) {
        return VendorResolver.resolve(emf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile()
                .conflictOnConstraint();
    }

    /** Whether {@code db} returns the rows of a write (R-WRT-50). */
    private static ReturningShape returningShape(TckDatabase db) {
        try (EntityManagerFactory emf = JoinTestSupport.sessionFactory(db)) {
            return VendorResolver.resolve(emf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile()
                    .returning(WriteStatement.UPDATE).orElse(null);
        }
    }

    /** The columns of {@code key} after its first, as {@code onConflict} takes them. */
    @SuppressWarnings("unchecked")
    private static <M, E> ColumnField<M, E, ?>[] rest(List<ColumnField<M, E, ?>> key) {
        return key.subList(1, key.size()).toArray(ColumnField[]::new);
    }

    /**
     * Whether {@code emf}'s built-in profile reports a vendor that detects a conflict on any unique key, MySQL, whose
     * count is 1 for each conflicting row skipped, filtered out or left unchanged and 2 for each changed (R-WRT-35).
     */
    private static boolean anyKey(EntityManagerFactory emf) {
        return !VendorResolver.resolve(emf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile()
                .conflictTargetHonoured();
    }

    /**
     * Whether {@code emf}'s built-in profile reports a vendor whose conflict update's {@code where} reads the values
     * earlier assignments wrote, MySQL (R-WRT-34).
     */
    private static boolean seesEarlierAssignments(EntityManagerFactory emf) {
        return VendorResolver.resolve(emf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile()
                .conflictWhereSeesEarlierAssignments();
    }

    private static void assertRefused(Outcome o, String reason) {
        assertThat(o.failure()).isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
            assertThat(e.code()).isEqualTo(MqCode.MQ1804);
            assertThat(e.getMessage()).contains(reason);
        });
        assertThat(o.sql()).isEmpty();
    }

    private static Outcome run(InsertProbes p, ModelInsert<InsAssignedEntity, ?> insert) {
        return run(p, insert, ModelQueryConfig.defaults());
    }

    /** Seeds the target, then runs {@code insert} in its own transaction. */
    private static Outcome run(InsertProbes p, ModelInsert<InsAssignedEntity, ?> insert, ModelQueryConfig config) {
        p.jdbc("delete from ins_assigned");
        p.jdbc("insert into ins_assigned (id, code, name, version) values (1, 'c1', 'N1', 0)");
        p.jdbc("insert into ins_assigned (id, code, name, version) values (2, 'c2', 'N2', 0)");
        p.forget();
        try {
            long count = p.factory().fromTransaction(session ->
                    ModelQueryExecutor.create(session, InsAssignedEntity.class, config).insert(insert));
            return new Outcome(count, null, inserts(p));
        } catch (RuntimeException e) {
            return new Outcome(null, e, inserts(p));
        }
    }

    /** Runs {@code insert} in a rolled-back transaction of a probe session. */
    private static <E> long inRolledBack(InsertProbes p, Class<E> root, ModelInsert<E, ?> insert) {
        return p.factory().fromSession(session -> {
            session.getTransaction().begin();
            try {
                return ModelQueryExecutor.create(session, root, ModelQueryConfig.defaults()).insert(insert);
            } finally {
                session.getTransaction().rollback();
            }
        });
    }

    /** The inserts and merges recorded since the last {@link InsertProbes#forget()}. */
    private static List<String> inserts(InsertProbes p) {
        return p.statements().stream().filter(sql -> sql.startsWith("insert") || sql.startsWith("merge")).toList();
    }

    /** The archive row 9001 as {@code id|customerId|status|version}. */
    private static String archived(EntityManager em) {
        Object[] row = em.createQuery("select a.id, a.customer.id, a.status, a.version from OrderArchiveEntity a"
                + " where a.id = 9001", Object[].class).getSingleResult();
        return String.join("|", Arrays.stream(row).map(String::valueOf).toList());
    }
}
