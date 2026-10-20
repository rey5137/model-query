package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.RowSelection;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import jakarta.persistence.Tuple;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Generated treat nodes, downcast joins and subtype values (R-GEN-41, R-GEN-42, D-154). */
class TreatModelTest {

    private static final JavaFileObject ENTITIES = source("treatgen.Entities", """
            package treatgen;
            import jakarta.persistence.*;
            public class Entities {
                @Entity @Inheritance public static class Payment { @Id public Long id; }
                @Entity public static class CardPayment extends Payment {
                    public String last4;
                    public java.sql.Timestamp created;
                    @ManyToOne public Issuer issuer;
                }
                @Entity public static class BankPayment extends Payment {
                    public String account;
                    @ManyToOne public Issuer issuer;
                }
                @Entity @Inheritance public static class Issuer { @Id public Long id; public String name; }
                @Entity public static class PremiumIssuer extends Issuer { public String code; }
                @Entity public static class Account {
                    @Id public Long id;
                    @ManyToOne public Payment payment;
                    @ManyToOne public CardPayment card;
                }
            }
            """);

    @Test
    void ac_gen_34_columns_joins_and_filter_paths_share_one_treated_root() throws Exception {
        Compilation compilation = compile(ENTITIES, issuer(), model("PaymentView", """
                @FilterColumn(name = "LAST4_RAW", path = "last4", treat = CardPayment.class)
                @FilterColumn(name = "ISSUER_FILTER", path = "issuer.name", treat = CardPayment.class)
                @QueryModel(root = Payment.class)
                public record PaymentView(@PrimaryKey Long id,
                        @Column(treat = CardPayment.class) String last4,
                        @Column(treat = CardPayment.class) java.time.Instant created,
                        @Join(treat = CardPayment.class) Optional<IssuerView> issuer) {}
                """));
        assertThat(compilation).succeeded();
        String code = compact(compilation, "treatgen.QPaymentView");
        assertThat(code).contains("TableField<Payment,CardPayment>CARD_PAYMENT_TREAT=ROOT.treat(CardPayment.class)",
                "OrderedColumnField<PaymentView,CardPayment,String>LAST4=ColumnField.of(PaymentView.class,"
                        + "CARD_PAYMENT_TREAT,\"last4\",String.class).named(\"last4\")",
                "ColumnField.of(PaymentView.class,CARD_PAYMENT_TREAT,\"created\",Instant.class,Timestamp.class,"
                        + "InstantTimestampConverter.INSTANCE)",
                "TableField.<CardPayment,Issuer>join(CARD_PAYMENT_TREAT,\"issuer\",JoinType.LEFT)"
                        + ".presentBy(QIssuerView.KEY)",
                "ISSUER_FILTER=ColumnField.of(PaymentView.class,ISSUER_TABLE,\"name\",String.class)",
                "ALL=SelectSet.of(ID,LAST4,CREATED)", "DEFAULT=ALL");
        assertThat(code.indexOf("CARD_PAYMENT_TREAT=ROOT.treat"))
                .isLessThan(code.indexOf("ISSUER_TABLE="));
        assertThat(code.split("ROOT.treat\\(CardPayment.class\\)", -1)).hasSize(2);
        Class<?> q = ProcessorHarness.classes(compilation).loadClass("treatgen.QPaymentView");
        assertThat(index(q).select("last4")).contains(field(q, "LAST4"));
        assertThat(index(q).filter("issuer.name")).isPresent();
        assertThat(set(q, "ALL").fields()).contains(field(q, "LAST4"), field(q, "CREATED"));
        assertThat(set(q, "DEFAULT").fields()).contains(field(q, "LAST4"));
    }

    @Test
    void ac_gen_34_filter_only_and_distinct_subtypes_get_their_own_nodes() throws Exception {
        Compilation compilation = compile(ENTITIES, model("PaymentView", """
                @FilterColumn(name = "CARD_ISSUER", path = "issuer.name", alias = "cardIssuer",
                        treat = CardPayment.class)
                @FilterColumn(name = "BANK_ISSUER", path = "issuer.name", alias = "bankIssuer",
                        treat = BankPayment.class)
                @QueryModel(root = Payment.class)
                public record PaymentView(@PrimaryKey Long id) {}
                """));
        assertThat(compilation).succeeded();
        assertThat(compact(compilation, "treatgen.QPaymentView")).contains(
                "CARD_PAYMENT_TREAT=ROOT.treat(CardPayment.class)",
                "BANK_PAYMENT_TREAT=ROOT.treat(BankPayment.class)",
                "TableField.<CardPayment,Issuer>join(CARD_PAYMENT_TREAT,\"issuer\",JoinType.LEFT).as(\"cardIssuer\")",
                "TableField.<BankPayment,Issuer>join(BANK_PAYMENT_TREAT,\"issuer\",JoinType.LEFT).as(\"bankIssuer\")",
                "ALL=SelectSet.of(ID)");
        Class<?> q = ProcessorHarness.classes(compilation).loadClass("treatgen.QPaymentView");
        assertThat(index(q).select("issuer.name")).isEmpty();
        assertThat(index(q).filterNames()).contains(
                "treat(treatgen.Entities$CardPayment).issuer.name",
                "treat(treatgen.Entities$BankPayment).issuer.name");
    }

    @Test
    void ac_gen_34_downcast_joins_treat_before_presence_but_supertype_joins_stay_plain() throws Exception {
        Compilation compilation = compile(ENTITIES,
                model("CardView", """
                        @QueryModel(root = CardPayment.class)
                        public record CardView(@PrimaryKey Long id, String last4) {}
                        """),
                model("PaymentView", """
                        @QueryModel(root = Payment.class)
                        public record PaymentView(@PrimaryKey Long id) {}
                        """),
                model("AccountView", """
                        @QueryModel(root = Account.class)
                        public record AccountView(@PrimaryKey Long id,
                                @Join(attribute = "payment", alias = "card") Optional<CardView> down,
                                @Join(attribute = "card") Optional<PaymentView> up,
                                @Join(attribute = "card", alias = "same") Optional<CardView> same) {}
                        """));
        assertThat(compilation).succeeded();
        String code = compact(compilation, "treatgen.QAccountView");
        assertThat(code).contains("TableField<Payment,CardPayment>DOWN_TABLE=TableField.<Account,Payment>"
                        + "join(ROOT,\"payment\",JoinType.LEFT).as(\"card\").named(\"down\")"
                        + ".treat(CardPayment.class).presentBy(QCardView.KEY)",
                "TableField<Account,Payment>UP_TABLE=TableField.<Account,Payment>"
                        + "join(ROOT,\"card\",JoinType.LEFT).as(\"up\").presentBy(QPaymentView.KEY)",
                "TableField<Account,CardPayment>SAME_TABLE=TableField.<Account,CardPayment>"
                        + "join(ROOT,\"card\",JoinType.LEFT).as(\"same\").presentBy(QCardView.KEY)");
        Class<?> q = ProcessorHarness.classes(compilation).loadClass("treatgen.QAccountView");
        // The downcast keeps its join's property path, so the join set and its columns index under it (D-55).
        assertThat(index(q).select("down.last4")).contains(field(q, "DOWN_LAST4"));
        assertThat(index(q).set("down")).isPresent();
    }

    @Test
    void ac_gen_34_nested_treated_columns_and_owner_treat_downcast_joins_compose() throws Exception {
        Compilation compilation = nested(false);
        assertThat(compilation).succeeded();
        assertThat(compact(compilation, "treatgen.QAccountView")).contains(
                "OrderedColumnField<AccountView,CardPayment,String>PAYMENT_LAST4=QPaymentView.LAST4"
                        + ".withTable(AccountView.class,QPaymentView.CARD_PAYMENT_TREAT.withParent(PAYMENT_TABLE))",
                "TableField<Issuer,PremiumIssuer>PAYMENT_ISSUER_TABLE=TableField.<CardPayment,Issuer>"
                        + "join(QPaymentView.CARD_PAYMENT_TREAT.withParent(PAYMENT_TABLE),\"issuer\",JoinType.LEFT)"
                        + ".named(\"issuer\").treat(PremiumIssuer.class).presentBy(QIssuerView.KEY)");
        Class<?> q = ProcessorHarness.classes(compilation).loadClass("treatgen.QAccountView");
        assertThat(index(q).select("payment.issuer.code")).contains(field(q, "PAYMENT_ISSUER_CODE"));
        Row row = row(List.of(field(q, "ID"), field(q, "PAYMENT_ID"), field(q, "PAYMENT_LAST4"),
                field(q, "PAYMENT_ISSUER_ID"), field(q, "PAYMENT_ISSUER_CODE")),
                List.of(1L, 2L, "1234", 3L, "P"));
        Object account = mapper(q).map(row);
        Object payment = ((Optional<?>) account.getClass().getMethod("payment").invoke(account)).orElseThrow();
        assertThat(payment.getClass().getMethod("last4").invoke(payment)).isEqualTo("1234");
        Object issuer = ((Optional<?>) payment.getClass().getMethod("issuer").invoke(payment)).orElseThrow();
        assertThat(issuer.getClass().getMethod("code").invoke(issuer)).isEqualTo("P");
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "filter", "join", "subtype"})
    void ac_gen_34_treat_constant_clashes_report_mq3015(String clash) {
        String extra = switch (clash) {
            case "field" -> ", @Column(attribute = \"last4\", treat = CardPayment.class) String cardPaymentTreat";
            case "join" -> ", @Join(treat = CardPayment.class, prefix = \"CARD_PAYMENT_TREAT\")"
                    + " Optional<IssuerView> issuer";
            case "subtype" -> ", @Column(treat = Other.CardPayment.class) String other";
            default -> "";
        };
        String annotation = clash.equals("filter")
                ? "@FilterColumn(name = \"CARD_PAYMENT_TREAT\", path = \"last4\", treat = CardPayment.class)" : "";
        Compilation compilation = compile(ENTITIES, issuer(), source("treatgen.Other", """
                package treatgen;
                public class Other {
                    @jakarta.persistence.Entity public static class CardPayment extends Entities.Payment {
                        public String other;
                    }
                }
                """), model("PaymentView", annotation + """
                @QueryModel(root = Payment.class)
                public record PaymentView(@PrimaryKey Long id,
                        @Column(treat = CardPayment.class) String last4%s) {}
                """.formatted(extra)));
        assertThat(errors(compilation)).hasSize(1).allMatch(message -> message.startsWith("MQ3015:"));
        assertThat(errors(compilation).get(0)).contains("CARD_PAYMENT_TREAT");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_gen_35_subtype_is_a_class_expression_in_sets_fields_and_the_mapper(boolean jspecify) throws Exception {
        Compilation compilation = ProcessorHarness.compileWithOptions(
                List.of("-Amodelquery.jspecify=" + jspecify), ENTITIES, model("PaymentView", """
                        @QueryModel(root = Payment.class)
                        public record PaymentView(@PrimaryKey Long id,
                                @Subtype Class<? extends Payment> kind) {}
                        """));
        assertThat(compilation).succeeded();
        assertThat(compact(compilation, "treatgen.QPaymentView")).contains(
                "ExpressionField<PaymentView,Class<?extendsPayment>>KIND=Expr.typeOf(PaymentView.class,ROOT)"
                        + ".named(\"kind\")", "ALL=SelectSet.of(ID,KIND)", "DEFAULT=ALL", "row.get(KIND)");
        ClassLoader loader = ProcessorHarness.classes(compilation);
        Class<?> q = loader.loadClass("treatgen.QPaymentView");
        Class<?> card = loader.loadClass("treatgen.Entities$CardPayment");
        assertThat(set(q, "DEFAULT").fields()).contains(field(q, "KIND"));
        assertThat(index(q).select("kind")).contains(field(q, "KIND"));
        Object result = mapper(q).map(ProcessorHarness.row(Map.of(field(q, "ID"), 1L, field(q, "KIND"), card)));
        assertThat(result.getClass().getMethod("kind").invoke(result)).isSameAs(card);
    }

    @Test
    void ac_gen_35_grouped_subtype_is_a_group_key() throws Exception {
        Compilation compilation = compile(ENTITIES, model("Summary", """
                @QueryModel(root = Payment.class)
                public record Summary(@GroupBy @Subtype Class<? extends Payment> kind,
                        @Aggregate(fn = AggregateFunction.COUNT) Long count) {}
                """));
        assertThat(compilation).succeeded();
        assertThat(compact(compilation, "treatgen.QSummary"))
                .contains("GROUP_KEYS=SelectSet.of(KIND)", "ALL=SelectSet.of(KIND)");
    }

    @Test
    void ac_gen_35_class_mapper_assigns_subtype_and_can_omit_select_sets() throws Exception {
        Compilation compilation = compile(ENTITIES, model("PaymentView", """
                @QueryModel(root = Payment.class, generateSelectSets = false)
                public class PaymentView {
                    @PrimaryKey private Long id;
                    @Subtype private Class<? extends Payment> kind;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Class<? extends Payment> getKind() { return kind; }
                    public void setKind(Class<? extends Payment> kind) { this.kind = kind; }
                }
                """));
        assertThat(compilation).succeeded();
        assertThat(generated(compilation, "treatgen.QPaymentView")).doesNotContain(" DEFAULT ");
        ClassLoader loader = ProcessorHarness.classes(compilation);
        Class<?> q = loader.loadClass("treatgen.QPaymentView");
        Class<?> bank = loader.loadClass("treatgen.Entities$BankPayment");
        Object result = mapper(q).map(ProcessorHarness.row(Map.of(field(q, "KIND"), bank)));
        assertThat(result.getClass().getMethod("getKind").invoke(result)).isSameAs(bank);
        assertThat(index(q).select("kind")).contains(field(q, "KIND"));
    }

    @Test
    void ac_gen_35_nested_subtype_expressions_map_at_every_join_depth() throws Exception {
        Compilation compilation = nested(true);
        assertThat(compilation).succeeded();
        ClassLoader loader = ProcessorHarness.classes(compilation);
        Class<?> q = loader.loadClass("treatgen.QAccountView");
        Class<?> card = loader.loadClass("treatgen.Entities$CardPayment");
        Class<?> premium = loader.loadClass("treatgen.Entities$PremiumIssuer");
        Row row = row(List.of(field(q, "ID"), field(q, "PAYMENT_ID"), field(q, "PAYMENT_KIND"),
                field(q, "PAYMENT_ISSUER_ID"), field(q, "PAYMENT_ISSUER_KIND")),
                List.of(1L, 2L, card, 3L, premium));
        Object result = mapper(q).map(row);
        Object payment = ((Optional<?>) result.getClass().getMethod("payment").invoke(result)).orElseThrow();
        Object issuer = ((Optional<?>) payment.getClass().getMethod("issuer").invoke(payment)).orElseThrow();
        assertThat(payment.getClass().getMethod("kind").invoke(payment)).isSameAs(card);
        assertThat(issuer.getClass().getMethod("kind").invoke(issuer)).isSameAs(premium);
        var scope = q.getDeclaredMethod("scope$PAYMENT", Row.class);
        scope.setAccessible(true);
        Row scoped = (Row) scope.invoke(null, row);
        SelectField<?, ?> kind = field(loader.loadClass("treatgen.QPaymentView"), "KIND");
        assertThat(scoped.raw(kind)).isSameAs(card);
        assertThat(scoped.isSelected(kind)).isTrue();
        Row absent = (Row) scope.invoke(null, row(List.of(field(q, "PAYMENT_ID")), Arrays.asList((Object) null)));
        assertThat(absent.get(kind)).isNull();
        assertThat(absent.raw(kind)).isNull();
        assertThat(absent.isSelected(kind)).isFalse();
        assertThat(compact(compilation, "treatgen.QAccountView")).contains(".named(\"payment.kind\")",
                ".named(\"payment.issuer.kind\")");
        assertThat(index(q).select("payment.kind")).contains(field(q, "PAYMENT_KIND"));
        assertThat(index(q).select("payment.issuer.kind")).contains(field(q, "PAYMENT_ISSUER_KIND"));
        Class<?> outer = loader.loadClass("treatgen.QPaymentView");
        assertThat(index(outer).select("kind")).contains(field(outer, "KIND"));
        assertThat(index(outer).select("issuer.kind")).contains(field(outer, "ISSUER_KIND"));
    }

    @Test
    void ac_gen_35_subtype_through_two_joins_to_one_nested_model_is_named_by_each_join() throws Exception {
        Compilation compilation = compile(ENTITIES, model("PaymentView", """
                @QueryModel(root = Payment.class)
                public record PaymentView(@PrimaryKey Long id, @Subtype Class<? extends Payment> kind) {}
                """), model("AccountView", """
                @QueryModel(root = Account.class)
                public record AccountView(@PrimaryKey Long id, @Join Optional<PaymentView> payment,
                        @Join Optional<PaymentView> card) {}
                """));
        assertThat(compilation).succeeded();
        Class<?> q = ProcessorHarness.classes(compilation).loadClass("treatgen.QAccountView");
        assertThat(index(q).select("payment.kind")).contains(field(q, "PAYMENT_KIND"));
        assertThat(index(q).select("card.kind")).contains(field(q, "CARD_KIND"));
    }

    @Test
    void ac_src_39_a_source_model_over_a_polymorphic_root_generates_treat_and_subtype_fields() throws Exception {
        Compilation compilation = compile(ENTITIES, model("PaymentSource", """
                @FilterColumn(name = "LAST4_FILTER", path = "last4", treat = CardPayment.class)
                @SourceModel(root = Payment.class)
                public record PaymentSource(@PrimaryKey Long id,
                        @Column(treat = CardPayment.class) String last4,
                        @Subtype Class<? extends Payment> kind) {}
                """));
        assertThat(compilation).succeeded();
        String code = compact(compilation, "treatgen.QPaymentSource");
        assertThat(code).contains("TableField<Payment,CardPayment>CARD_PAYMENT_TREAT=ROOT.treat(CardPayment.class)",
                "ColumnField.of(PaymentSource.class,CARD_PAYMENT_TREAT,\"last4\",String.class).named(\"last4\")",
                "LAST4_FILTER=ColumnField.of(PaymentSource.class,CARD_PAYMENT_TREAT,\"last4\",String.class)",
                "KIND=Expr.typeOf(PaymentSource.class,ROOT).named(\"kind\")", "ALL=SelectSet.of(ID,LAST4,KIND)",
                "SourceQuery.builder(PaymentSource.class,ROOT,ALL,KEY)");
        Class<?> q = ProcessorHarness.classes(compilation).loadClass("treatgen.QPaymentSource");
        assertThat(set(q, "ALL").fields()).contains(field(q, "LAST4"), field(q, "KIND"));
    }

    private static Compilation nested(boolean subtype) {
        String kind = subtype ? ", @Subtype Class<? extends %s> kind" : "";
        return compile(ENTITIES, model("IssuerView", """
                @QueryModel(root = PremiumIssuer.class)
                public record IssuerView(@PrimaryKey Long id, String code%s) {}
                """.formatted(kind.formatted("PremiumIssuer"))), model("PaymentView", """
                @QueryModel(root = Payment.class)
                public record PaymentView(@PrimaryKey Long id,
                        @Column(treat = CardPayment.class) String last4,
                        @Join(treat = CardPayment.class) Optional<IssuerView> issuer%s) {}
                """.formatted(kind.formatted("Payment"))), model("AccountView", """
                @QueryModel(root = Account.class)
                public record AccountView(@PrimaryKey Long id, @Join Optional<PaymentView> payment) {}
                """));
    }

    private static JavaFileObject issuer() {
        return model("IssuerView", """
                @QueryModel(root = Issuer.class)
                public record IssuerView(@PrimaryKey Long id, String name) {}
                """);
    }

    private static JavaFileObject model(String name, String declaration) {
        return source("treatgen." + name, """
                package treatgen;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                import treatgen.Entities.*;
                """ + declaration);
    }

    private static String compact(Compilation compilation, String qModel) {
        return generated(compilation, qModel).replaceAll("\\s+", "").replace("Entities.", "");
    }

    @SuppressWarnings("unchecked")
    private static SelectField<Object, ?> field(Class<?> q, String constant) throws ReflectiveOperationException {
        return (SelectField<Object, ?>) q.getField(constant).get(null);
    }

    @SuppressWarnings("unchecked")
    private static SelectSet<Object> set(Class<?> q, String constant) throws ReflectiveOperationException {
        return (SelectSet<Object>) q.getField(constant).get(null);
    }

    @SuppressWarnings("unchecked")
    private static FieldIndex<Object> index(Class<?> q) throws ReflectiveOperationException {
        return (FieldIndex<Object>) q.getMethod("fields").invoke(null);
    }

    private static RowMapper<?> mapper(Class<?> q) throws ReflectiveOperationException {
        return (RowMapper<?>) q.getField("MAPPER").get(null);
    }

    private static Row row(List<SelectField<?, ?>> fields, List<Object> values) {
        Tuple tuple = (Tuple) Proxy.newProxyInstance(Tuple.class.getClassLoader(), new Class<?>[]{Tuple.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("get") && arguments[0] instanceof String alias) {
                        return values.get(Integer.parseInt(alias.replaceAll("\\D", "")));
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        return RowSelection.of(fields).row(tuple);
    }
}
