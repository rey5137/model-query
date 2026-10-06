package com.rey.modelquery.tck.vnd.ins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import java.util.List;
import java.util.Map;
import org.hibernate.Session;
import org.hibernate.exception.SQLGrammarException;
import org.hibernate.id.BulkInsertionCapableIdentifierGenerator;
import org.hibernate.id.IdentityGenerator;
import org.hibernate.id.enhanced.NoopOptimizer;
import org.hibernate.id.enhanced.PooledOptimizer;
import org.hibernate.id.enhanced.SequenceStyleGenerator;
import org.hibernate.id.enhanced.TableGenerator;
import org.hibernate.query.SemanticException;
import org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter;

/**
 * The D-116 vendor spike, generators: what Hibernate does with each id generator on insert-values and insert-select,
 * pinned per Tier-1 vendor on the Hibernate the build runs (6.6 by default, 7.x nightly). Plain HQL, no insert API.
 */
class InsertGeneratorSpikeTest {

    /** Each generated root and its table. */
    private static final Map<Class<?>, String> GENERATED = Map.of(InsIdentityEntity.class, "ins_identity",
            InsPooledEntity.class, "ins_pooled", InsSequenceEntity.class, "ins_sequence", InsTableEntity.class,
            "ins_table", InsUuidEntity.class, "ins_uuid");

    /** The roots whose keys the engine can draw before the statement: every generated one but {@code IDENTITY}. */
    private static final List<Class<?>> PREGENERATED =
            List.of(InsPooledEntity.class, InsSequenceEntity.class, InsTableEntity.class, InsUuidEntity.class);

    @TckTest
    void d_116_the_persister_reports_each_root_s_generator(TckDatabase db) {
        boolean mysql = db.vendor() == TckVendor.MYSQL;
        try (InsertProbes p = InsertProbes.open(db)) {
            assertThat(p.generator(InsIdentityEntity.class)).isExactlyInstanceOf(IdentityGenerator.class);
            assertThat(p.generator(InsAssignedEntity.class).getClass().getName())
                    .isEqualTo(InsertProbes.hibernateMajor() >= 7 ? "org.hibernate.generator.Assigned"
                            : "org.hibernate.id.Assigned");
            var pooled = (SequenceStyleGenerator) p.generator(InsPooledEntity.class);
            assertThat(pooled.getOptimizer()).isExactlyInstanceOf(PooledOptimizer.class);
            assertThat(pooled.getDatabaseStructure().isPhysicalSequence()).isEqualTo(!mysql);
            var sequence = (SequenceStyleGenerator) p.generator(InsSequenceEntity.class);
            assertThat(sequence.getOptimizer()).isExactlyInstanceOf(NoopOptimizer.class);
            assertThat(sequence.getDatabaseStructure().isPhysicalSequence()).isEqualTo(!mysql);
            assertThat(p.generator(InsTableEntity.class)).isExactlyInstanceOf(TableGenerator.class);
            assertThat(p.generator(InsUuidEntity.class).getClass().getName()).isEqualTo(
                    InsertProbes.hibernateMajor() >= 7 ? "org.hibernate.id.uuid.UuidGenerator"
                            : "org.hibernate.id.UUIDGenerator");
            // Hibernate's own "bulk insertion capable" flag is not the insert-select plan: it holds for any physical
            // sequence, pooled included, yet a pooled one runs the temporary-table plan (see the probes below)
            assertThat(pooled.supportsBulkInsertionIdentifierGeneration()).isEqualTo(!mysql);
            assertThat(sequence.supportsBulkInsertionIdentifierGeneration()).isEqualTo(!mysql);
            assertThat(p.generator(InsTableEntity.class))
                    .isNotInstanceOf(BulkInsertionCapableIdentifierGenerator.class);
            assertThat(p.generator(InsUuidEntity.class)).isNotInstanceOf(BulkInsertionCapableIdentifierGenerator.class);
        }
    }

    @TckTest
    void d_116_insert_values_generates_an_omitted_id_and_writes_one_statement(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            GENERATED.forEach((root, table) -> {
                int count = p.inTransaction(s -> insertTwo(s, root, null));
                assertThat(count).as(table).isEqualTo(2);
                assertThat(inserts(p.statements())).as(table).hasSize(1);
                assertThat(p.<List<Object>>inTransaction(s -> ids(s, root))).as(table)
                        .doesNotContainNull().doesNotHaveDuplicates().hasSize(2);
            });
            // the provider writes the version seed for a versioned root the statement does not name
            assertThat(p.rows("select version from ins_identity")).containsExactly("0", "0");
        }
    }

    @TckTest
    void d_116_insert_values_writes_pregenerated_keys_and_skips_the_generator(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            for (Class<?> root : PREGENERATED) {
                List<Object> keys = p.inTransaction(s -> {
                    List<Object> drawn = p.pregenerate(s, root, 2);
                    p.forget();
                    assertThat(insertTwo(s, root, drawn)).isEqualTo(2);
                    return drawn;
                });
                assertThat(p.statements()).as(root.getSimpleName()).hasSize(1);
                assertThat(p.statements().get(0)).as(root.getSimpleName()).startsWith("insert into ");
                assertThat(p.<List<Object>>inTransaction(s -> ids(s, root))).as(root.getSimpleName()).isEqualTo(keys);
                // the generator's state is shared, so a later persist draws a key past the pre-generated ones
                Object persisted = p.inTransaction(s -> {
                    Object entity = instantiate(root);
                    s.persist(entity);
                    s.flush();
                    return s.getIdentifier(entity);
                });
                assertThat(keys).as(root.getSimpleName()).doesNotContain(persisted);
            }
        }
    }

    @TckTest
    void d_116_insert_values_accepts_an_explicit_identity_value(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertThat(p.<Integer>inTransaction(s -> s.createQuery(
                    "insert into InsIdentityEntity (id, code, name) values (100, 'c1', 'N1'), (101, 'c2', 'N2')")
                    .executeUpdate())).isEqualTo(2);
            p.inTransaction(s -> s.createQuery("insert into InsIdentityEntity (code, name) values ('c3', 'N3')")
                    .executeUpdate());
            // H2 and PostgreSQL do not advance the identity past an explicit value, so a later row can collide
            assertThat(p.rows("select id from ins_identity where code = 'c3'"))
                    .containsExactly(db.vendor() == TckVendor.MYSQL ? "102" : "1");
        }
    }

    @TckTest
    void d_116_insert_select_renders_identity_and_assigned_ids_in_one_statement(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertThat(p.<Integer>inTransaction(s -> s.createQuery("insert into InsIdentityEntity (code, name)"
                    + " select s.code, s.name from InsSourceEntity s").executeUpdate())).isEqualTo(3);
            assertThat(p.statements()).hasSize(1);
            assertThat(p.<Integer>inTransaction(s -> s.createQuery("insert into InsAssignedEntity (id, code, name)"
                    + " select s.id, s.code, s.name from InsSourceEntity s").executeUpdate())).isEqualTo(3);
            assertThat(p.statements()).hasSize(1);
            assertThat(p.rows("select id, code, version from ins_assigned order by id"))
                    .containsExactly("1|s1|0", "2|s2|0", "3|s3|0");
        }
    }

    @TckTest
    void d_116_insert_select_renders_a_physical_increment_one_sequence_inline(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            String hql = "insert into InsSequenceEntity (code, name) select s.code, s.name from InsSourceEntity s";
            if (db.vendor() != TckVendor.MYSQL) {
                assertThat(p.<Integer>inTransaction(s -> s.createQuery(hql).executeUpdate())).isEqualTo(3);
                assertThat(p.statements()).singleElement().asString().contains(db.vendor() == TckVendor.H2
                        ? "next value for ins_sequence_seq" : "nextval('ins_sequence_seq')");
            } else if (InsertProbes.hibernateMajor() >= 7) {
                // MySQL's sequence is a table: 7.x runs the temporary-table plan, a generator round trip per row
                assertThat(p.<Integer>inTransaction(s -> s.createQuery(hql).executeUpdate())).isEqualTo(3);
                assertThat(p.statements()).filteredOn(sql -> sql.startsWith("update HTE_ins_sequence")).hasSize(3);
            } else if (BaseSqmToSqlAstConverter.class.desiredAssertionStatus()) {
                assertThatThrownBy(() -> p.inTransaction(s -> s.createQuery(hql).executeUpdate()))
                        .isExactlyInstanceOf(AssertionError.class);
            } else {
                // 6.6 without assertions writes row numbers as ids, ignoring the generator's state
                assertThat(p.<Integer>inTransaction(s -> s.createQuery(hql).executeUpdate())).isEqualTo(3);
                assertThat(p.statements()).singleElement().asString().contains("row_number() over()");
            }
        }
    }

    @TckTest
    void d_116_insert_select_runs_a_pooled_sequence_through_a_temporary_table_plan(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertThat(p.<Integer>inTransaction(s -> s.createQuery("insert into InsPooledEntity (code, name)"
                    + " select s.code, s.name from InsSourceEntity s").executeUpdate())).isEqualTo(3);
            if (db.vendor() == TckVendor.POSTGRESQL) {
                assertThat(p.statements()).singleElement().asString().startsWith("with base_HTE_ins_pooled ");
            } else {
                assertThat(p.statements()).anySatisfy(sql -> assertThat(sql).startsWith("insert into HTE_ins_pooled"));
                assertThat(p.statements()).filteredOn(sql -> sql.startsWith("update HTE_ins_pooled")).hasSize(3);
            }
            assertThat(p.<List<Object>>inTransaction(s -> ids(s, InsPooledEntity.class)))
                    .doesNotHaveDuplicates().hasSize(3);
        }
    }

    @TckTest
    void d_116_insert_select_rejects_table_and_uuid_generators(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            for (String root : List.of("InsTableEntity", "InsUuidEntity")) {
                assertThatThrownBy(() -> p.inTransaction(s -> s.createQuery("insert into " + root + " (code, name)"
                        + " select s.code, s.name from InsSourceEntity s").executeUpdate()))
                        .isExactlyInstanceOf(IllegalArgumentException.class)
                        .cause().isExactlyInstanceOf(SemanticException.class)
                        .hasMessageContaining("without bulk insertion capable identifier generator");
            }
        }
    }

    @TckTest
    void d_116_maps_id_writes_through_the_id_and_not_through_the_association(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            p.inTransaction(s -> s.createQuery("insert into InsAssignedEntity (id, code, name)"
                    + " select s.id, s.code, s.name from InsSourceEntity s").executeUpdate());
            assertThat(p.<Integer>inTransaction(s -> s.createQuery(
                    "insert into InsMapsIdEntity (id, note) values (1, 'n1')").executeUpdate())).isEqualTo(1);
            assertThatThrownBy(() -> p.inTransaction(s -> s.createQuery(
                    "insert into InsMapsIdEntity (id, parent, note) values (2, :parent, 'n2')")
                    .setParameter("parent", s.getReference(InsAssignedEntity.class, 2L)).executeUpdate()))
                    .isExactlyInstanceOf(SQLGrammarException.class)
                    .hasMessageContaining("(id,id,note)");
            assertThatThrownBy(() -> p.inTransaction(s -> s.createQuery("insert into InsMapsIdEntity (parent, note)"
                    + " select a, a.name from InsAssignedEntity a where a.id > 1").executeUpdate()))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .cause().isExactlyInstanceOf(SemanticException.class)
                    .hasMessageContaining("without bulk insertion capable identifier generator");
            assertThat(p.<Integer>inTransaction(s -> s.createQuery("insert into InsMapsIdEntity (id, note)"
                    + " select a.id, a.name from InsAssignedEntity a where a.id > 1").executeUpdate())).isEqualTo(2);
            assertThat(p.rows("select id, note from ins_maps_id order by id")).containsExactly("1|n1", "2|S2", "3|S3");
        }
    }

    /** Inserts rows {@code c1} and {@code c2} into {@code root}, with {@code ids} when given. */
    private static int insertTwo(Session session, Class<?> root, List<Object> ids) {
        String entity = root.getSimpleName();
        if (ids == null) {
            return session.createQuery("insert into " + entity + " (code, name) values ('c1', 'N1'), ('c2', 'N2')")
                    .executeUpdate();
        }
        return session.createQuery("insert into " + entity
                        + " (id, code, name) values (:first, 'c1', 'N1'), (:second, 'c2', 'N2')")
                .setParameter("first", ids.get(0))
                .setParameter("second", ids.get(1))
                .executeUpdate();
    }

    private static List<Object> ids(Session session, Class<?> root) {
        return session.createQuery("select e.id from " + root.getSimpleName() + " e order by e.code", Object.class)
                .getResultList();
    }

    private static List<String> inserts(List<String> statements) {
        return statements.stream().filter(sql -> sql.startsWith("insert into ")).toList();
    }

    private static Object instantiate(Class<?> root) {
        try {
            Object entity = root.getDeclaredConstructor().newInstance();
            root.getDeclaredField("code").set(entity, "persisted");
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
