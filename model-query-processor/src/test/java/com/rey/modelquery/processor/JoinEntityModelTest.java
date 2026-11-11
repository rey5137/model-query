package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.ScalarField;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** Processor forms of entity joins (R-PROC-46, AC-PROC-48, AC-SRC-53, AC-SRC-56). */
class JoinEntityModelTest {

    private static final JavaFileObject NODE = source("shop.Node", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class Node {
                @Id Long id;
                String name;
            }
            """);
    private static final JavaFileObject EDGE = source("shop.Edge", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class Edge {
                @Id Long nodeId;
                Long managerId;
            }
            """);
    private static final JavaFileObject SOURCE = source("shop.ManagerEdge", """
            package shop;
            import com.rey.modelquery.annotations.*;
            @SourceModel(root = Edge.class)
            public record ManagerEdge(@PrimaryKey Long nodeId, Long managerId) {}
            """);
    private static final JavaFileObject PROFILE = source("shop.Profile", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class Profile {
                @Id Long id;
                String firstName;
                boolean deleted;
                @ManyToOne ProfileType type;
            }
            """);
    private static final JavaFileObject TYPE = source("shop.ProfileType", """
            package shop;
            import jakarta.persistence.*;
            @Entity public class ProfileType {
                @Id Long id;
                String name;
            }
            """);

    private static Compilation model(String declaration) {
        return compile(NODE, EDGE, SOURCE, PROFILE, TYPE, source("shop.NodeDetail", """
                package shop;
                import com.rey.modelquery.annotations.*;
                """ + declaration));
    }

    @Test
    void ac_proc_48_entity_columns_and_to_one_paths_follow_the_rfc_generated_shape() throws Exception {
        Compilation compilation = model("""
                @QueryModel(root = Node.class, generateChanges = true, generateInserts = true)
                @JoinSource(name = "edge", source = ManagerEdge.class,
                        on = @JoinSource.On(column = "id", key = "nodeId"))
                @JoinEntity(name = "manager", source = "edge", entity = Profile.class,
                        on = @JoinEntity.On(field = "managerId"))
                @FilterColumn(name = "MANAGER_DELETED", entityJoin = "manager", path = "deleted")
                @FilterColumn(name = "MANAGER_TYPE_NAME", entityJoin = "manager", path = "type.name")
                @FilterColumn(name = "MANAGER_REQUIRED_TYPE", entityJoin = "manager", path = "type.id",
                        alias = "requiredType", joinType = JoinKind.INNER)
                public record NodeDetail(@PrimaryKey Long id, String name,
                        @SourceColumn(join = "edge", field = "managerId") Long managerId,
                        @Column(entityJoin = "manager", attribute = "firstName") String managerFirstName,
                        @Column(entityJoin = "manager", attribute = "type.name") String managerType) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        var index = (FieldIndex<?>) classes(compilation).loadClass("shop.QNodeDetail")
                .getMethod("fields").invoke(null);
        assertThat(index.resolve(List.of("managerType")).unknown()).isEmpty();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        assertThat(generated)
                .contains("EntityJoin<NodeDetail, Profile> MANAGER = EntityJoin.of(\"manager\", Profile.class, "
                        + "JoinKind.LEFT, List.of(EntityJoin.on(MANAGER_ID, \"id\")));")
                .contains("MANAGER_TYPE_TABLE = TableField.<Profile, ProfileType>join("
                        + "MANAGER.root(), \"type\", JoinType.LEFT)")
                .contains("MANAGER_FIRST_NAME = ColumnField.of(NodeDetail.class, MANAGER.root(), "
                        + "\"firstName\", String.class).named(\"managerFirstName\")")
                .contains("MANAGER_REQUIRED_TYPE_TABLE = TableField.<Profile, ProfileType>join("
                        + "MANAGER.root(), \"type\", JoinType.INNER).as(\"requiredType\")")
                .contains("MANAGER_TYPE = ColumnField.of(NodeDetail.class, MANAGER_TYPE_TABLE, "
                        + "\"name\", String.class).named(\"managerType\")")
                .contains("MANAGER_DELETED = ColumnField.of(NodeDetail.class, MANAGER.root(), "
                        + "\"deleted\", Boolean.class)")
                .contains(".joinSource(EDGE, edge).joinEntity(MANAGER)");
        assertThat(generatedFlat(compilation, "shop.NodeDetailChanges"))
                .doesNotContain("managerFirstName(", "managerType(", "managerId(");
        assertThat(generated).contains("a joined entity's column");
    }

    @Test
    void ac_proc_48_repeated_entity_joins_keep_names_kinds_and_unexposed_anchors() throws Exception {
        Compilation compilation = model("""
                @QueryModel(root = Node.class)
                @JoinSource(name = "edge", source = ManagerEdge.class,
                        on = @JoinSource.On(column = "id", key = "nodeId"))
                @JoinEntity(name = "manager", source = "edge", entity = Profile.class,
                        on = @JoinEntity.On(field = "managerId", id = "id"))
                @JoinEntity(name = "other", source = "edge", entity = Profile.class, type = JoinKind.INNER,
                        on = @JoinEntity.On(field = "nodeId"))
                @FilterColumn(name = "MANAGER_DELETED", entityJoin = "manager", path = "deleted")
                @FilterColumn(name = "OTHER_DELETED", entityJoin = "other", path = "deleted")
                public record NodeDetail(@PrimaryKey Long id,
                        @Column(entityJoin = "manager", attribute = "firstName") String managerName,
                        @Column(entityJoin = "other", attribute = "firstName") String otherName,
                        @Column(entityJoin = "manager", attribute = "type.name") String managerType,
                        @Column(entityJoin = "other", attribute = "type.name") String otherType) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        var index = (FieldIndex<?>) classes(compilation).loadClass("shop.QNodeDetail")
                .getMethod("fields").invoke(null);
        assertThat(index.resolve(List.of("managerType", "otherType")).unknown()).isEmpty();
        assertThat(generatedFlat(compilation, "shop.QNodeDetail"))
                .contains("EntityJoin.on(ColumnField.over(NodeDetail.class, EDGE.root(), "
                        + "QManagerEdge.MANAGER_ID), \"id\")")
                .contains("EntityJoin.of(\"other\", Profile.class, JoinKind.INNER")
                .contains("MANAGER.root(), \"firstName\"")
                .contains("OTHER.root(), \"firstName\"")
                .contains(".joinEntity(MANAGER).joinEntity(OTHER)")
                .contains(".filterOnly(MANAGER_DELETED, OTHER_DELETED)");
    }

    @Test
    void ac_proc_49_column_anchored_joins_and_when_follow_the_rfc_generated_shape() {
        JavaFileObject invoice = source("shop.Invoice", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class Invoice {
                    @Id Long id;
                    Long repId;
                    Boolean repActive;
                    Long legacyRepId;
                    @ManyToOne Customer customer;
                }
                """);
        JavaFileObject customer = source("shop.Customer", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class Customer { @Id Long id; Long repId; }
                """);
        JavaFileObject employee = source("shop.Employee", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class Employee { @Id Long id; String name; }
                """);
        Compilation compilation = compile(invoice, customer, employee, source("shop.InvoiceRow", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = Invoice.class)
                @JoinEntity(name = "rep", entity = Employee.class, on = @JoinEntity.On(column = "repId"),
                        when = @JoinEntity.When(column = "repActive", value = "true"))
                @JoinEntity(name = "legacy", entity = Employee.class, type = JoinKind.INNER,
                        on = @JoinEntity.On(column = "legacyRepId"))
                @JoinEntity(name = "owner", entity = Employee.class,
                        on = @JoinEntity.On(column = "customerRepId"))
                @FilterColumn(name = "repId", path = "repId")
                @FilterColumn(name = "repActive", path = "repActive")
                @FilterColumn(name = "customerRepId", path = "customer.repId")
                @FilterColumn(name = "REP_NAME", entityJoin = "rep", path = "name")
                public record InvoiceRow(@PrimaryKey Long id,
                        @Column(attribute = "legacyRepId") Long legacyRepId) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QInvoiceRow");
        assertThat(generated)
                .contains("EntityJoin<InvoiceRow, Employee> REP = EntityJoin.of(\"rep\", Employee.class, "
                        + "JoinKind.LEFT, List.of(EntityJoin.on(repId, \"id\"))).when(repActive, true);")
                .contains("EntityJoin<InvoiceRow, Employee> LEGACY = EntityJoin.of(\"legacy\", Employee.class, "
                        + "JoinKind.INNER, List.of(EntityJoin.on(LEGACY_REP_ID, \"id\")));")
                .contains("EntityJoin<InvoiceRow, Employee> OWNER = EntityJoin.of(\"owner\", Employee.class, "
                        + "JoinKind.LEFT, List.of(EntityJoin.on(customerRepId, \"id\")));")
                .contains("REP_NAME = ColumnField.of(InvoiceRow.class, REP.root(), \"name\", String.class)")
                .contains(".joinEntity(REP).joinEntity(LEGACY).joinEntity(OWNER)");
        assertThat(generated).doesNotContain("query(SourceQuery");
    }

    @Test
    void ac_proc_49_a_source_anchored_join_takes_a_when_on_a_root_column() {
        Compilation compilation = model("""
                @QueryModel(root = Node.class)
                @JoinSource(name = "edge", source = ManagerEdge.class,
                        on = @JoinSource.On(column = "id", key = "nodeId"))
                @JoinEntity(name = "manager", source = "edge", entity = Profile.class,
                        on = @JoinEntity.On(field = "managerId"),
                        when = @JoinEntity.When(column = "id", value = "7"))
                public record NodeDetail(@PrimaryKey Long id) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QNodeDetail"))
                .contains("QManagerEdge.MANAGER_ID), \"id\"))).when(ID, 7L)");
    }

    @Test
    void ac_proc_50_joined_aggregates_use_the_join_root_and_reuse_to_one_tables() {
        Compilation compilation = model("""
                @QueryModel(root = Node.class)
                @JoinEntity(name = "manager", entity = Profile.class, type = JoinKind.INNER,
                        on = @JoinEntity.On(column = "ID"))
                @FilterColumn(name = "ID", path = "id")
                public record NodeDetail(
                        @GroupBy @Column(entityJoin = "manager", attribute = "type.id") Long typeId,
                        @Aggregate(fn = AggregateFunction.COUNT, entityJoin = "manager", attribute = "id") Long total,
                        @Aggregate(fn = AggregateFunction.COUNT, entityJoin = "manager") Long rows,
                        @Aggregate(fn = AggregateFunction.COUNT, entityJoin = "manager", attribute = "id",
                                distinct = true) Long distinctIds,
                        @Aggregate(fn = AggregateFunction.MAX, entityJoin = "manager",
                                attribute = "type.name") String name,
                        @Aggregate(fn = AggregateFunction.FIRST_BY, entityJoin = "manager", attribute = "firstName",
                                orderBy = "type.name desc nulls last") String first) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        assertThat(generated)
                .contains("TOTAL = Agg.count( ColumnField.of(NodeDetail.class, MANAGER.root(), \"id\", Long.class))")
                .contains("ROWS = Agg.<NodeDetail>count(MANAGER.root())")
                .contains("DISTINCT_IDS = Agg.countDistinct( ColumnField.of(NodeDetail.class, MANAGER.root(), ")
                .contains("NAME = Agg.max( ColumnField.of(NodeDetail.class, MANAGER_TYPE_TABLE, "
                        + "\"name\", String.class))")
                .contains("ColumnField.of(NodeDetail.class, MANAGER_TYPE_TABLE, "
                        + "\"name\", String.class).desc().nullsLast()")
                .contains(".joinEntity(MANAGER)");
        assertThat(generated.indexOf("MANAGER_TYPE_TABLE ="))
                .isEqualTo(generated.lastIndexOf("MANAGER_TYPE_TABLE ="));
    }

    @Test
    void ac_proc_50_empty_entity_join_generates_the_same_source_as_the_default() {
        String declaration = """
                @QueryModel(root = Node.class, singleGroup = true)
                public record NodeDetail(
                        @Aggregate(fn = AggregateFunction.COUNT) Long total,
                        @Aggregate(fn = AggregateFunction.MAX, attribute = "name") String name) {}
                """;
        Compilation implicit = model(declaration);
        Compilation explicit = model(declaration.replace("@Aggregate(", "@Aggregate(entityJoin = \"\", "));
        assertThat(implicit).succeededWithoutWarnings();
        assertThat(explicit).succeededWithoutWarnings();
        assertThat(generatedFlat(explicit, "shop.QNodeDetail"))
                .isEqualTo(generatedFlat(implicit, "shop.QNodeDetail"));
    }

    @Test
    void ac_proc_50_aggregate_only_to_one_paths_declare_their_table() {
        Compilation compilation = model("""
                @QueryModel(root = Node.class, singleGroup = true)
                @JoinEntity(name = "manager", entity = Profile.class, type = JoinKind.INNER,
                        on = @JoinEntity.On(column = "ID"))
                @FilterColumn(name = "ID", path = "id")
                public record NodeDetail(
                        @Aggregate(fn = AggregateFunction.MAX, entityJoin = "manager",
                                attribute = "type.name") String name,
                        @Aggregate(fn = AggregateFunction.FIRST_BY, entityJoin = "manager", attribute = "firstName",
                                orderBy = "type.id") String first) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "shop.QNodeDetail"))
                .contains("MANAGER_TYPE_TABLE = TableField.<Profile, ProfileType>join("
                        + "MANAGER.root(), \"type\", JoinType.LEFT)")
                .contains("NAME = Agg.max( ColumnField.of(NodeDetail.class, MANAGER_TYPE_TABLE,")
                .contains("ColumnField.of(NodeDetail.class, MANAGER_TYPE_TABLE, \"id\", Long.class).asc()");
    }

    private static final JavaFileObject PROFILE_VIEW = source("shop.ProfileView", """
            package shop;
            import com.rey.modelquery.annotations.*;
            @QueryModel(root = Profile.class)
            public record ProfileView(
                    @PrimaryKey Long id,
                    String firstName) {}
            """);

    private static final JavaFileObject PROFILE_TYPE_VIEW = source("shop.ProfileTypeView", """
            package shop;
            import com.rey.modelquery.annotations.*;
            @QueryModel(root = ProfileType.class)
            public record ProfileTypeView(
                    @PrimaryKey Long id,
                    String name) {}
            """);

    private static final JavaFileObject PROFILE_WITH_TYPE = source("shop.ProfileWithType", """
            package shop;
            import com.rey.modelquery.annotations.*;
            import java.util.Optional;
            @QueryModel(root = Profile.class)
            public record ProfileWithType(
                    @PrimaryKey Long id,
                    String firstName,
                    @Join Optional<ProfileTypeView> type) {}
            """);

    private static Compilation modelWith(JavaFileObject extra, String declaration) {
        return compile(NODE, EDGE, SOURCE, PROFILE, TYPE, extra, source("shop.NodeDetail", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                """ + declaration));
    }

    private static Compilation modelWith(JavaFileObject extra1, JavaFileObject extra2, String declaration) {
        return compile(NODE, EDGE, SOURCE, PROFILE, TYPE, extra1, extra2, source("shop.NodeDetail", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                """ + declaration));
    }

    @Test
    void ac_gen_36_table_constant_nested_columns_and_mapper() throws Exception {
        Compilation compilation = modelWith(PROFILE_VIEW, """
                @QueryModel(root = Node.class)
                @JoinEntity(name = "manager", entity = Profile.class, on = @JoinEntity.On(column = "id"))
                public record NodeDetail(
                        @PrimaryKey Long id,
                        @Join(entityJoin = "manager") Optional<ProfileView> manager) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        assertThat(generated)
                .contains("EntityJoin<NodeDetail, Profile> MANAGER = EntityJoin.of(\"manager\", Profile.class,")
                .contains("MANAGER_TABLE = MANAGER.root().named(\"manager\").presentBy(QProfileView.KEY);")
                .contains("MANAGER_FIRST_NAME = QProfileView.FIRST_NAME.withTable(NodeDetail.class, MANAGER_TABLE);")
                .contains("MANAGER_JOIN =")
                .contains("Row manager = row.scoped(MANAGER_TABLE);")
                .contains("manager.get(QProfileView.ID) == null ? Optional.empty() : "
                        + "Optional.of(QProfileView.MAPPER.map(manager))")
                .contains(".joinSet(MANAGER_TABLE, MANAGER_SET)");
    }

    @Test
    void ac_gen_36_nested_model_with_to_one_join_below() {
        Compilation compilation = modelWith(PROFILE_TYPE_VIEW, PROFILE_WITH_TYPE, """
                @QueryModel(root = Node.class)
                @JoinEntity(name = "manager", entity = Profile.class, on = @JoinEntity.On(column = "id"))
                public record NodeDetail(
                        @PrimaryKey Long id,
                        @Join(entityJoin = "manager") Optional<ProfileWithType> manager) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        assertThat(generated)
                .contains("MANAGER_TYPE_TABLE = QProfileWithType.TYPE_TABLE.withParent(MANAGER_TABLE);")
                .contains("MANAGER_TYPE_NAME = QProfileWithType.TYPE_NAME.withTable(NodeDetail.class, "
                        + "MANAGER_TYPE_TABLE);");
    }

    @Test
    void ac_gen_36_repeated_entity_joins_keep_separate_table_constants_rule_6() {
        Compilation compilation = modelWith(PROFILE_VIEW, """
                @QueryModel(root = Node.class)
                @JoinEntity(name = "manager", entity = Profile.class, on = @JoinEntity.On(column = "id"))
                public record NodeDetail(
                        @PrimaryKey Long id,
                        @Join(entityJoin = "manager") Optional<ProfileView> primaryManager,
                        @Join(entityJoin = "manager") Optional<ProfileView> secondaryManager) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        assertThat(generated)
                .contains("PRIMARY_MANAGER_TABLE = "
                        + "MANAGER.root().named(\"primaryManager\").presentBy(QProfileView.KEY);")
                .contains("SECONDARY_MANAGER_TABLE = "
                        + "MANAGER.root().named(\"secondaryManager\").presentBy(QProfileView.KEY);")
                .contains("Row primaryManager = row.scoped(PRIMARY_MANAGER_TABLE);")
                .contains("Row secondaryManager = row.scoped(SECONDARY_MANAGER_TABLE);");
    }

    @Test
    void ac_gen_36_coexisting_flat_and_nested_entity_join_fields_rule_7() {
        Compilation compilation = modelWith(PROFILE_VIEW, """
                @QueryModel(root = Node.class)
                @JoinEntity(name = "manager", entity = Profile.class, on = @JoinEntity.On(column = "id"))
                public record NodeDetail(
                        @PrimaryKey Long id,
                        @Column(entityJoin = "manager", attribute = "firstName") String flatFirstName,
                        @Join(entityJoin = "manager") Optional<ProfileView> manager) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        assertThat(generated)
                .contains("FLAT_FIRST_NAME = ColumnField.of(NodeDetail.class, MANAGER.root(), "
                        + "\"firstName\", String.class).named(\"flatFirstName\")")
                .contains("MANAGER_TABLE = MANAGER.root().named(\"manager\").presentBy(QProfileView.KEY);")
                .contains("MANAGER_FIRST_NAME = QProfileView.FIRST_NAME.withTable(NodeDetail.class, MANAGER_TABLE);");
    }

    @Test
    void ac_gen_36_filter_column_over_association_alongside_nested_entity_join() throws Exception {
        JavaFileObject customer = source("shop.Customer", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class Customer {
                    @Id Long id;
                    String name;
                    String email;
                }
                """);
        JavaFileObject shopOrder = source("shop.ShopOrder", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class ShopOrder {
                    @Id Long id;
                    Long referrerId;
                    @ManyToOne Customer customer;
                }
                """);
        JavaFileObject customerView = source("shop.CustomerView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = Customer.class)
                public record CustomerView(@PrimaryKey Long id, String name) {}
                """);
        Compilation compilation = compile(customer, shopOrder, customerView, source("shop.OrderRow", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = ShopOrder.class)
                @JoinEntity(name = "buyer", entity = Customer.class, on = @JoinEntity.On(column = "referrerId"))
                @FilterColumn(name = "customerEmail", alias = "orderCustomer", path = "customer.email")
                public record OrderRow(
                        @PrimaryKey Long id,
                        Long referrerId,
                        @Join(entityJoin = "buyer") Optional<CustomerView> customer) {}
                """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QOrderRow");
        assertThat(generated)
                .contains("ORDER_CUSTOMER_TABLE = TableField.<ShopOrder, Customer>join("
                        + "ROOT, \"customer\", JoinType.LEFT).as(\"orderCustomer\");")
                .contains("customerEmail = ColumnField.of(OrderRow.class, ORDER_CUSTOMER_TABLE, "
                        + "\"email\", String.class);")
                .contains("CUSTOMER_TABLE = BUYER.root().named(\"customer\").presentBy(QCustomerView.KEY);");
        var index = (FieldIndex<?>) classes(compilation).loadClass("shop.QOrderRow")
                .getMethod("fields").invoke(null);
        assertThat(index.filterNames()).contains("customer.email");
    }

    @Test
    void ac_gen_36_filter_column_over_association_without_alias_collides_with_nested_entity_join_table() {
        JavaFileObject customer = source("shop.Customer", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class Customer {
                    @Id Long id;
                    String name;
                    String email;
                }
                """);
        JavaFileObject shopOrder = source("shop.ShopOrder", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class ShopOrder {
                    @Id Long id;
                    Long referrerId;
                    @ManyToOne Customer customer;
                }
                """);
        JavaFileObject customerView = source("shop.CustomerView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = Customer.class)
                public record CustomerView(@PrimaryKey Long id, String name) {}
                """);
        Compilation compilation = compile(customer, shopOrder, customerView, source("shop.OrderRow", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = ShopOrder.class)
                @JoinEntity(name = "buyer", entity = Customer.class, on = @JoinEntity.On(column = "referrerId"))
                @FilterColumn(name = "customerEmail", path = "customer.email")
                public record OrderRow(
                        @PrimaryKey Long id,
                        Long referrerId,
                        @Join(entityJoin = "buyer") Optional<CustomerView> customer) {}
                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("MQ3015:");
        assertThat(compilation).hadErrorContaining(
                "OrderRow @FilterColumn(customerEmail): constant CUSTOMER_TABLE of the join 'customer' of "
                        + "@FilterColumn(customerEmail) is also generated for the join customer; "
                        + "set another @Join(prefix) or @FilterColumn(alias)");
    }

    @Test
    void ac_gen_36_column_anchored_late_entity_join_places_table_constant_after_join_constant() {
        Compilation compilation = modelWith(PROFILE_VIEW, """
                @QueryModel(root = Node.class)
                @JoinEntity(name = "manager", entity = Profile.class, on = @JoinEntity.On(column = "id"))
                public record NodeDetail(
                        @PrimaryKey Long id,
                        @Join(entityJoin = "manager") Optional<ProfileView> manager) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        int entityJoinIdx = generated.indexOf("MANAGER = EntityJoin.of(");
        int tableIdx = generated.indexOf("MANAGER_TABLE =");
        assertThat(entityJoinIdx).isGreaterThanOrEqualTo(0);
        assertThat(tableIdx).isGreaterThan(entityJoinIdx);
    }

    @Test
    void ac_gen_36_inner_nested_join_two_levels_below_inner_entity_join_generates_without_refusal() {
        JavaFileObject targetItem = source("shop.TargetItem", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class TargetItem { @Id Long id; String label; }
                """);
        JavaFileObject targetItemView = source("shop.TargetItemView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @QueryModel(root = TargetItem.class)
                public record TargetItemView(@PrimaryKey Long id, String label) {}
                """);
        JavaFileObject middleItem = source("shop.MiddleItem", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class MiddleItem { @Id Long id; @ManyToOne TargetItem target; }
                """);
        JavaFileObject middleItemView = source("shop.MiddleItemView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = MiddleItem.class)
                public record MiddleItemView(@PrimaryKey Long id,
                        @Join(type = JoinKind.INNER) Optional<TargetItemView> target) {}
                """);
        JavaFileObject rootItem = source("shop.RootItem", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class RootItem { @Id Long id; @ManyToOne MiddleItem middle; }
                """);
        JavaFileObject rootView = source("shop.RootView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                import java.util.Optional;
                @QueryModel(root = RootItem.class)
                public record RootView(@PrimaryKey Long id,
                        @Join(type = JoinKind.INNER) Optional<MiddleItemView> middle) {}
                """);
        Compilation compilation = compile(
                NODE, targetItem, targetItemView, middleItem, middleItemView, rootItem, rootView,
                source("shop.NodeDetail", """
                        package shop;
                        import com.rey.modelquery.annotations.*;
                        import java.util.Optional;
                        @QueryModel(root = Node.class)
                        @JoinEntity(name = "rootItem", entity = RootItem.class, type = JoinKind.INNER,
                                on = @JoinEntity.On(column = "id"))
                        public record NodeDetail(@PrimaryKey Long id,
                                @Join(entityJoin = "rootItem") Optional<RootView> rootItem) {}
                        """));
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        assertThat(generated)
                .contains("ROOT_ITEM_TABLE = ROOT_ITEM.root().named(\"rootItem\").presentBy(QRootView.KEY);")
                .contains("ROOT_ITEM_MIDDLE_TABLE = QRootView.MIDDLE_TABLE.withParent(ROOT_ITEM_TABLE);")
                .contains("ROOT_ITEM_MIDDLE_TARGET_TABLE = "
                        + "QRootView.MIDDLE_TARGET_TABLE.withParent(ROOT_ITEM_MIDDLE_TABLE);");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ac_gen_36_grouped_model_with_entity_join_throws_mq1409_when_group_keys_lack_presence_key() throws Exception {
        Compilation compilation = modelWith(PROFILE_VIEW, """
                @QueryModel(root = Node.class)
                @JoinEntity(name = "manager", entity = Profile.class, on = @JoinEntity.On(column = "id"))
                public record NodeDetail(
                        @PrimaryKey @GroupBy Long id,
                        @Aggregate(fn = AggregateFunction.COUNT) Long count,
                        @Join(entityJoin = "manager") Optional<ProfileView> manager) {}
                """);
        assertThat(compilation).succeededWithoutWarnings();
        String generated = generatedFlat(compilation, "shop.QNodeDetail");
        assertThat(generated).contains("GROUP_KEYS = SelectSet.of(ID);");

        Class<?> qNodeDetail = classes(compilation).loadClass("shop.QNodeDetail");
        var builder = (ModelQuery.Builder) qNodeDetail.getMethod("query").invoke(null);
        var id = (ScalarField) qNodeDetail.getField("ID").get(null);
        var count = (SelectField) qNodeDetail.getField("COUNT").get(null);
        var managerFirstName = (ScalarField) qNodeDetail.getField("MANAGER_FIRST_NAME").get(null);

        assertThatThrownBy(() -> builder.select(SelectSet.of(id, count, managerFirstName))
                .groupBy(id, managerFirstName).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1409))
                .hasMessage("MQ1409: NodeDetail.firstName: selected on a grouped query under the presentBy "
                        + "joined entity 'manager' (Profile), whose key column id is not in groupBy; "
                        + "a grouped query adds no presence key, so group by the key or select the column through "
                        + "a join without presentBy");
    }
}
