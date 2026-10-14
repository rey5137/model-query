package com.rey.modelquery.tck.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.fch.QCustomerOrders;
import com.rey.modelquery.tck.fch.QOrderLines;
import com.rey.modelquery.tck.vnd.ins.InsIdentityEntity;
import com.rey.modelquery.tck.vnd.ins.InsSequenceEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AC-INS-12: the portability check runs calls and swept definitions on every target's offline factory with no
 * container, classifies each by its recording then its cause chain, and reports one line per call and target.
 */
class PortabilityCheckTest {

    record Customer(Long id, String name) {}

    record NewCode(String code) {}

    private static final Target H2 = Target.of(DatabaseVendor.H2, "2.3.232");
    private static final Target POSTGRESQL = Target.of(DatabaseVendor.POSTGRESQL, "15.4");
    private static final Target MYSQL = Target.of(DatabaseVendor.MYSQL, "8.0.36");

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Customer, CustomerEntity, Long> ID =
            ColumnField.of(Customer.class, CUSTOMERS, "id", Long.class);
    private static final ColumnField<Customer, CustomerEntity, String> NAME =
            ColumnField.of(Customer.class, CUSTOMERS, "name", String.class);
    private static final ColumnField<Customer, CustomerEntity, String> COUNTRY =
            ColumnField.of(Customer.class, CUSTOMERS, "country", String.class);

    private static final ModelQuery.Builder<CustomerEntity, Long, Customer> BY_ID = ModelQuery
            .builder(CUSTOMERS, row -> new Customer(row.get(ID), row.get(NAME)))
            .select(SelectSet.of(ID, NAME))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());

    /** No order and no primary key, so {@code first(query)} refuses it with {@code MQ2203} before any statement. */
    private static final ModelQuery<CustomerEntity, ?, Customer> UNORDERED = ModelQuery
            .builder(CUSTOMERS, row -> new Customer(row.get(ID), row.get(NAME)))
            .select(SelectSet.of(ID, NAME))
            .build();

    private static final ModelUpdate<CustomerEntity, ?> RENAME_IN_CHUNKS = ModelUpdate.builder(CUSTOMERS)
            .primaryKey(PrimaryKey.of(ID)).set(NAME, "renamed").where(f -> f.eq(COUNTRY, "NL"))
            .chunked(ChunkOptions.size(10).commitEachChunk()).build();

    private static final TableField<InsSequenceEntity, InsSequenceEntity> SEQUENCED_ROOT =
            TableField.root(InsSequenceEntity.class);
    private static final TableField<InsIdentityEntity, InsIdentityEntity> IDENTITY_ROOT =
            TableField.root(InsIdentityEntity.class);

    /** The definitions a sweep reads: one per type and mark, and one that runs no statement. */
    static final class Swept {
        static final ModelQuery<CustomerEntity, Long, Customer> ALL = BY_ID.build();
        static final ModelQuery<CustomerEntity, Long, Customer> DEEP =
                BY_ID.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100)).build();
        static final ModelQuery<CustomerEntity, Long, ?> WITH_ORDERS = QCustomerOrders.query()
                .fetch(FetchPlan.of(QCustomerOrders.ALL).child(QCustomerOrders.ORDERS, FetchPlan.of(QOrderLines.ALL)))
                .build();
        static final ModelUpdate<CustomerEntity, ?> RENAME = ModelUpdate.builder(CUSTOMERS)
                .primaryKey(PrimaryKey.of(ID)).set(NAME, "renamed").where(f -> f.eq(COUNTRY, "NL")).build();
        static final ModelUpdate<CustomerEntity, ?> RENAME_NOBODY = ModelUpdate.builder(CUSTOMERS)
                .primaryKey(PrimaryKey.of(ID)).set(NAME, "renamed").whereKeys(List.of()).build();
        static final ModelDelete<CustomerEntity, ?> DELETE = ModelDelete.builder(CUSTOMERS)
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.eq(COUNTRY, "NL")).build();
        static final ModelPersist<InsSequenceEntity, Long, NewCode> PERSIST_SEQUENCED = ModelPersist.of(
                InsertColumns.<NewCode, InsSequenceEntity>of(SEQUENCED_ROOT).add(
                        ColumnField.of(NewCode.class, SEQUENCED_ROOT, "code", String.class), NewCode::code),
                Long.class, new NewCode("s1"));
        static final ModelPersist<InsIdentityEntity, Long, NewCode> PERSIST_IDENTITY = ModelPersist.of(
                InsertColumns.<NewCode, InsIdentityEntity>of(IDENTITY_ROOT).add(
                        ColumnField.of(NewCode.class, IDENTITY_ROOT, "code", String.class), NewCode::code),
                Long.class, new NewCode("i1"));
        static final String NOT_A_DEFINITION = "not swept";
        static final Object NOT_FINAL_NOR_STATIC = null;
    }

    /** A holder with fields but no definition. */
    static final class NoDefinitions {
        static final String NAME = "none";
    }

    private static PortabilityCheck check(ModelQueryConfig config) {
        List<Class<?>> classes = OfflineBootTest.TCK_UNIT.stream().<Class<?>>map(PortabilityCheckTest::load).toList();
        return PortabilityCheck.of(classes, config);
    }

    private static PortabilityCheck check() {
        return check(ModelQueryConfig.defaults()).targets(H2, POSTGRESQL, MYSQL);
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

    @Test
    void ac_ins_12_a_sweep_runs_each_definition_once_per_target_and_reports_one_line_each() {
        List<String> lines = report(check().sweep(Swept.class));

        // Eight definitions on three targets; the update of no key reaches no statement on any of them.
        assertThat(lines.get(0)).isEqualTo("Not portable: 3 of 24 results failed");
        assertThat(lines.subList(1, lines.size())).hasSize(24);
        for (Target target : List.of(H2, POSTGRESQL, MYSQL)) {
            String on = " on " + target + ": ";
            assertThat(lines).contains("Swept.RENAME_NOBODY update" + on + "NO_STATEMENT the call returned having "
                    + "reached no statement");
            assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("Swept.ALL list" + on + "REACHED select")
                    .contains("customers"));
            // A primary-key-first query and a query loading children read statements after the first.
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("Swept.DEEP list" + on + "REACHED (first statement only) select"));
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("Swept.WITH_ORDERS list" + on + "REACHED (first statement only) select"));
            // A drawn id's first statement is the draw; an identity insert's is the insert.
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("Swept.PERSIST_SEQUENCED persist" + on + "REACHED (first statement only) ")
                    .contains("ins_sequence_seq").doesNotContain("insert"));
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("Swept.PERSIST_IDENTITY persist" + on + "REACHED insert into ins_identity"));
        }
        // On a profile without targetTableInSubquery() a write reading its own table goes key-first (R-WRT-11).
        assertThat(lines).contains(
                "Swept.RENAME update on " + H2 + ": REACHED update customers ce1_0 set name=? where ce1_0.country=?",
                "Swept.RENAME update on " + MYSQL + ": REACHED (first statement only) update customers ce1_0 set "
                        + "name=? where ce1_0.country=?",
                "Swept.DELETE delete on " + POSTGRESQL + ": REACHED delete from customers ce1_0 where ce1_0.country=?",
                "Swept.DELETE delete on " + MYSQL + ": REACHED (first statement only) delete ce1_0 from customers "
                        + "ce1_0 where ce1_0.country=?");
        assertThat(lines).noneMatch(line -> line.contains("NOT_A_DEFINITION") || line.contains("NOT_FINAL"));
    }

    @Test
    void ac_ins_12_a_skip_leaves_a_definition_out_with_its_reason() {
        check().sweep(Swept.class).skip(Swept.class, "RENAME_NOBODY", "whereKeys of no key runs no statement")
                .assertPortable();

        // A refused call makes the report visible: the skipped definition prints one line with its reason, after
        // the results, and runs on no target.
        List<String> lines = report(check().sweep(Swept.class)
                .skip(Swept.class, "RENAME_NOBODY", "whereKeys of no key runs no statement")
                .call("refused", CustomerEntity.class, x -> x.first(UNORDERED)));
        assertThat(lines).last().isEqualTo("Swept.RENAME_NOBODY skipped: whereKeys of no key runs no statement");
        assertThat(lines).filteredOn(line -> line.startsWith("Swept.RENAME_NOBODY")).hasSize(1);
    }

    @Test
    void ac_ins_12_the_report_has_one_line_per_call_and_target_with_each_outcome() {
        List<String> lines = report(check()
                .call(CustomerEntity.class, x -> x.list(Swept.ALL, Limit.unlimited()))
                .call("refused", CustomerEntity.class, x -> x.first(UNORDERED))
                .call("swallowed then refused", CustomerEntity.class, x -> {
                    try {
                        x.list(Swept.ALL, Limit.unlimited());
                    } catch (RuntimeException swallowed) {
                        // the stub's stop, which a call must not hide
                    }
                    return x.first(UNORDERED);
                })
                .call("zero limit", CustomerEntity.class, x -> x.list(Swept.ALL, Limit.of(0)))
                .call("thrown", CustomerEntity.class, x -> {
                    throw new IllegalStateException("not the library's");
                }));

        assertThat(lines).hasSize(16).first().isEqualTo("Not portable: 12 of 15 results failed");
        assertThat(lines.subList(1, 4)).allSatisfy(line -> assertThat(line).startsWith("call 1 (CustomerEntity) on ")
                .contains(": REACHED select").contains("customers"));
        String refusal = "MQ2203: Customer: first(query) needs an orderBy or a primary key to choose the same row on "
                + "every database";
        for (Target target : List.of(H2, POSTGRESQL, MYSQL)) {
            String on = " on " + target + ": ";
            assertThat(lines).contains("refused" + on + "REFUSED " + refusal);
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("swallowed then refused" + on + "REFUSED_AFTER_STATEMENT " + refusal + " after select")
                    .contains("customers"));
            assertThat(lines).contains("zero limit" + on + "NO_STATEMENT the call returned having reached no "
                    + "statement");
            assertThat(lines).contains("thrown" + on + "HARNESS_ERROR java.lang.IllegalStateException: not the "
                    + "library's");
        }
    }

    @Test
    void ac_ins_12_a_lazy_result_is_drained_by_one_element_and_closed() {
        AtomicBoolean closed = new AtomicBoolean();
        check().call("lazy stream", CustomerEntity.class, x -> Stream.of(1)
                        .map(one -> x.list(Swept.ALL, Limit.unlimited())).onClose(() -> closed.set(true)))
                .call("lazy iterator", CustomerEntity.class, x -> new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public Object next() {
                        return x.list(Swept.ALL, Limit.unlimited());
                    }
                })
                .assertPortable();
        assertThat(closed).isTrue();
    }

    @Test
    void ac_ins_12_a_commit_each_chunk_call_runs_on_the_harness_chunk_transactions() {
        // The application's callback serves its own factory only, so it would be a false MQ4004 here.
        ChunkTransactions production = new ChunkTransactions() {
            @Override
            public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
                throw new AssertionError("the application's callback ran");
            }

            @Override
            public void checkServes(EntityManagerFactory emf) {
                throw new IllegalStateException("serves the application's factory only");
            }
        };
        check(ModelQueryConfig.defaults().chunkTransactions(production)).targets(H2, POSTGRESQL, MYSQL)
                .callOutsideTransaction("chunked", CustomerEntity.class, x -> x.update(RENAME_IN_CHUNKS))
                .assertPortable();

        // Without a callback configured, MQ4004 is the application's own refusal, and is reported.
        List<String> lines = report(check().callOutsideTransaction("chunked", CustomerEntity.class,
                x -> x.update(RENAME_IN_CHUNKS)));
        assertThat(lines.subList(1, lines.size())).hasSize(3)
                .allSatisfy(line -> assertThat(line).contains(": REFUSED MQ4004: "));
    }

    @Test
    void ac_ins_12_an_explicit_vendor_is_left_out_and_each_target_detected() {
        // A returning query takes no orderBy (MQ1809).
        var returning = ModelQuery.builder(CUSTOMERS, row -> new Customer(row.get(ID), row.get(NAME)))
                .select(SelectSet.of(ID, NAME)).primaryKey(PrimaryKey.of(ID)).build();
        // vendor(POSTGRESQL) would run PostgreSQL's profile on the MySQL target; detected, MySQL has no RETURNING.
        List<String> lines = report(check(ModelQueryConfig.defaults().vendor(DatabaseVendor.POSTGRESQL))
                .targets(POSTGRESQL, MYSQL)
                .call("returning", CustomerEntity.class, x -> x.updateReturning(Swept.RENAME, returning)));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith("returning on " + POSTGRESQL + ": REACHED update customers");
        assertThat(lines.get(2)).startsWith("returning on " + MYSQL + ": REFUSED MQ4011: ");
    }

    @Test
    void ac_ins_12_targets_default_to_the_declared_vendors_and_one_outside_them_is_mq4012() {
        var config = ModelQueryConfig.defaults().targetVendors(DatabaseVendor.H2, DatabaseVendor.POSTGRESQL);
        List<String> lines = report(check(config).call("zero limit", CustomerEntity.class,
                x -> x.list(Swept.ALL, Limit.of(0))));
        assertThat(lines.subList(1, lines.size())).containsExactly(
                "zero limit on H2 (dialect minimum): NO_STATEMENT the call returned having reached no statement",
                "zero limit on POSTGRESQL (dialect minimum): NO_STATEMENT the call returned having reached no "
                        + "statement");

        assertThatThrownBy(() -> check(config).targets(H2, MYSQL))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4012))
                .hasMessageContaining("MYSQL 8.0.36 is not one of the declared target vendors [H2, POSTGRESQL]");
    }

    @Test
    void ac_ins_12_an_empty_check_fails_loudly_before_any_call_runs() {
        assertThatThrownBy(() -> check(ModelQueryConfig.defaults()).sweep(Swept.class).assertPortable())
                .isInstanceOf(AssertionError.class)
                .hasMessage("Portability check set up wrongly, nothing ran:\n"
                        + "no targets: pass targets(...) or declare config.targetVendors(...)");
        assertThatThrownBy(() -> check().assertPortable())
                .isInstanceOf(AssertionError.class)
                .hasMessageEndingWith("\nno calls: add call(...) or sweep(...)");
        assertThatThrownBy(() -> check().sweep(NoDefinitions.class).assertPortable())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("\nsweep of " + NoDefinitions.class.getName()
                        + " finds no static final definition");
        assertThatThrownBy(() -> check().sweep(Swept.class)
                .skip(Swept.class, "MISSING", "gone")
                .skip(Swept.class, "NOT_A_DEFINITION", "a string")
                .skip(Swept.class, "ALL", " ")
                .skip(NoDefinitions.class, "NAME", "not swept either")
                .assertPortable())
                .isInstanceOf(AssertionError.class)
                .hasMessage("Portability check set up wrongly, nothing ran:\n"
                        + "skip of Swept.MISSING: " + Swept.class.getName() + " has no field MISSING\n"
                        + "skip of Swept.NOT_A_DEFINITION: not a static final definition\n"
                        + "skip of Swept.ALL gives no reason\n"
                        + "skip of NoDefinitions.NAME: not a static final definition");
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
