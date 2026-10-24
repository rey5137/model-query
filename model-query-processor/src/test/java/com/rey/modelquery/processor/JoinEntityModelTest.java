package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.FieldIndex;
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
}
