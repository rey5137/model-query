package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.source;

import com.google.testing.compile.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** Invalid processor forms of entity joins (AC-SRC-56, R-PROC-46). */
class EntityJoinChecksTest {

    private static final String SOURCE_JOIN = """
            @JoinSource(name = "edge", source = EdgeView.class,
                    on = @JoinSource.On(column = "id", key = "nodeId"))
            """;
    private static final String ENTITY_JOIN = """
            @JoinEntity(name = "manager", source = "edge", entity = Profile.class,
                    on = @JoinEntity.On(field = "managerId"))
            """;
    private static final String FIELDS = "@PrimaryKey Long id";
    private static final String PROFILE = "@Id Long id; String name;";

    private static Compilation model(String joins, String fields, String profile) {
        return model(joins, fields, profile, "Long managerId", "Long managerId", "", "@QueryModel");
    }

    private static Compilation model(String joins, String fields, String profile, String edgeField,
            String sourceField, String filters, String kind) {
        JavaFileObject node = source("shop.Node", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class Node { @Id Long id; }
                """);
        JavaFileObject edge = source("shop.Edge", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class Edge { @Id Long nodeId;
                """ + edgeField + "; }");
        JavaFileObject view = source("shop.EdgeView", """
                package shop;
                import com.rey.modelquery.annotations.*;
                @SourceModel(root = Edge.class)
                public record EdgeView(@PrimaryKey Long nodeId,
                """ + sourceField + ") {}");
        JavaFileObject target = source("shop.Profile", """
                package shop;
                import jakarta.persistence.*;
                @Entity public class Profile {
                """ + profile + " }");
        JavaFileObject part = source("shop.Key", """
                package shop;
                import jakarta.persistence.*;
                @Embeddable public class Key { Long tenant; Long id; }
                """);
        JavaFileObject converter = source("shop.CodeConverter", """
                package shop;
                import com.rey.modelquery.core.ColumnConverter;
                public class CodeConverter implements ColumnConverter<String, Long> {
                    public String toModel(Long value) { return value == null ? null : value.toString(); }
                    public Long toAttribute(String value) { return value == null ? null : Long.valueOf(value); }
                }
                """);
        return compile(node, edge, view, target, part, converter, source("shop.Detail", """
                package shop;
                import com.rey.modelquery.annotations.*;
                """ + kind + "(root = Node.class)\n" + joins + filters
                + "public record Detail(" + fields + ") {}"));
    }

    private static void rejected(Compilation compilation, String detail) {
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("MQ3046:");
        assertThat(compilation).hadErrorContaining(detail);
    }

    @Test
    void ac_src_56_reserved_entity_join_constants_are_mq3046() {
        for (String name : new String[] {"key", "all", "root"}) {
            rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("name = \"manager\"", "name = \"" + name + "\""),
                    FIELDS, PROFILE), "constant " + name.toUpperCase());
        }
    }

    @Test
    void ac_src_56_entity_join_constant_clashing_with_field_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS + ", @Column(attribute = \"id\") Long manager", PROFILE),
                "constant MANAGER");
    }

    @Test
    void ac_src_56_treat_on_joined_entity_column_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN,
                FIELDS + ", @Column(entityJoin = \"manager\", treat = Profile.class) String name", PROFILE),
                "treat cannot be combined with entityJoin");
    }

    @Test
    void ac_src_56_treat_on_joined_entity_filter_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, PROFILE, "Long managerId", "Long managerId",
                "@FilterColumn(name = \"NAME\", entityJoin = \"manager\", path = \"name\", treat = Profile.class)",
                "@QueryModel"), "treat cannot be combined with entityJoin");
    }

    @Test
    void ac_proc_48_bad_joined_column_path_reports_only_the_real_field() {
        Compilation compilation = model(SOURCE_JOIN + ENTITY_JOIN,
                FIELDS + ", @Column(entityJoin = \"manager\", attribute = \"nope\") String name", PROFILE);
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorCount(1);
        assertThat(compilation).hadErrorContaining("Detail.name:");
    }

    @Test
    void ac_proc_48_bad_joined_column_converter_is_reported_once() {
        Compilation compilation = model(SOURCE_JOIN + ENTITY_JOIN,
                FIELDS + ", @Column(entityJoin = \"manager\", converter = CodeConverter.class) String name", PROFILE);
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorCount(1);
        assertThat(compilation).hadErrorContaining("MQ3014:");
    }

    @Test
    void ac_src_56_partial_id_pairs_are_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("field = \"managerId\"",
                "field = \"managerId\", id = \"key.id\""), FIELDS, "@EmbeddedId Key key;"),
                "cover every basic id attribute exactly once");
    }

    @Test
    void ac_src_56_extra_id_pair_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("on = @JoinEntity.On(field = \"managerId\")",
                "on = {@JoinEntity.On(field = \"managerId\"), "
                        + "@JoinEntity.On(field = \"nodeId\")}"), FIELDS, PROFILE),
                "more than once");
    }

    @Test
    void ac_src_56_non_id_attribute_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("field = \"managerId\"",
                "field = \"managerId\", id = \"name\""), FIELDS, PROFILE), "must be a basic id attribute");
    }

    @Test
    void ac_src_56_association_id_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, "@Id @ManyToOne Node id;"),
                "must be a basic id attribute");
    }

    @Test
    void ac_src_56_converted_anchor_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, PROFILE, "Long managerId",
                "@Column(converter = CodeConverter.class) String managerId", "", "@QueryModel"), "has a converter");
    }

    @Test
    void ac_src_56_implicit_converter_on_anchor_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, "@Id java.sql.Timestamp id;", "java.sql.Timestamp managerId",
                "java.time.Instant managerId", "", "@QueryModel"), "has a converter");
    }

    @Test
    void ac_src_56_boxed_type_mismatch_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, "@Id Integer id;"), "different boxed types");
    }

    @Test
    void ac_src_56_primitive_and_boxed_types_are_equal() {
        Compilation compilation = model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, "@Id int id;", "int managerId",
                "Integer managerId", "", "@QueryModel");
        assertThat(compilation).succeededWithoutWarnings();
    }

    @Test
    void ac_src_56_unknown_anchor_join_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("source = \"edge\"", "source = \"missing\""), FIELDS, PROFILE),
                "source 'missing' must name a @JoinSource of this model");
    }

    @Test
    void ac_src_56_unknown_anchor_field_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("field = \"managerId\"", "field = \"missing\""), FIELDS,
                PROFILE), "unknown anchor field 'missing'");
    }

    @Test
    void ac_src_56_anchor_must_be_a_join_source_declared_on_this_model() {
        rejected(model(ENTITY_JOIN.replace("source = \"edge\"", "source = \"EdgeView\""), FIELDS, PROFILE),
                "must name a @JoinSource of this model");
    }

    @Test
    void ac_src_56_a_join_source_whose_model_failed_to_resolve_reports_only_its_own_error() {
        var compilation = model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, PROFILE, "Long managerId",
                "@Column(attribute = \"nope\") Long managerId", "", "@QueryModel");
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("nope");
        compilation.errors().forEach(error -> org.assertj.core.api.Assertions
                .assertThat(error.getMessage(java.util.Locale.ROOT)).doesNotContain("must name a @JoinSource"));
    }

    @Test
    void ac_src_56_entity_join_cannot_anchor_another_entity_join() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN
                + ENTITY_JOIN.replace("name = \"manager\"", "name = \"other\"")
                        .replace("source = \"edge\"", "source = \"manager\""), FIELDS, PROFILE),
                "source 'manager' must name a @JoinSource of this model");
    }

    @Test
    void ac_src_56_selected_to_many_path_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN,
                FIELDS + ", @Column(entityJoin = \"manager\", attribute = \"reports.id\") Long reportId",
                PROFILE + " @OneToMany java.util.List<Profile> reports;"), "to-many attribute");
    }

    @Test
    void ac_src_56_filter_to_many_path_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS,
                PROFILE + " @OneToMany java.util.List<Profile> reports;", "Long managerId", "Long managerId",
                "@FilterColumn(name = \"REPORT\", entityJoin = \"manager\", path = \"reports.id\")", "@QueryModel"),
                "to-many attribute");
    }

    @Test
    void ac_src_56_nested_join_on_entity_column_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN,
                FIELDS + ", @Join @Column(entityJoin = \"manager\", attribute = \"boss\") Detail boss",
                PROFILE + " @ManyToOne Profile boss;"), "a nested @Join on a joined entity is forbidden");
    }

    @Test
    void ac_src_56_primary_key_on_entity_column_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN,
                FIELDS + ", @PrimaryKey @Column(entityJoin = \"manager\", attribute = \"id\") Long managerId", PROFILE),
                "@PrimaryKey is forbidden");
    }

    @Test
    void ac_src_56_entity_join_on_source_model_is_mq3046() {
        rejected(model(ENTITY_JOIN, FIELDS, PROFILE, "Long managerId", "Long managerId", "", "@SourceModel"),
                "@JoinEntity needs a @QueryModel rooted at an entity");
    }

    @Test
    void ac_src_56_duplicate_entity_join_name_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN + ENTITY_JOIN, FIELDS, PROFILE), "duplicate join name");
    }

    @Test
    void ac_src_56_entity_and_source_share_the_join_namespace() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("name = \"manager\"", "name = \"edge\""), FIELDS, PROFILE),
                "duplicate join name");
    }

    @Test
    void ac_src_56_column_naming_unknown_entity_join_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN,
                FIELDS + ", @Column(entityJoin = \"missing\", attribute = \"name\") String managerName", PROFILE),
                "unknown entity join");
    }

    @Test
    void ac_src_56_filter_naming_unknown_entity_join_is_mq3046() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, PROFILE, "Long managerId", "Long managerId",
                "@FilterColumn(name = \"NAME\", entityJoin = \"missing\", path = \"name\")", "@QueryModel"),
                "unknown entity join");
    }

    @Test
    void ac_src_56_complete_embedded_id_pairs_are_accepted() {
        Compilation compilation = model(SOURCE_JOIN + """
                @JoinEntity(name = "manager", source = "edge", entity = Profile.class, on = {
                    @JoinEntity.On(field = "managerId", id = "key.id"),
                    @JoinEntity.On(field = "nodeId", id = "key.tenant")})
                """, FIELDS, "@EmbeddedId Key key;");
        assertThat(compilation).succeededWithoutWarnings();
    }

    @Test
    void ac_src_56_empty_id_pairs_are_mq3046() {
        rejected(model(SOURCE_JOIN + "@JoinEntity(name = \"manager\", source = \"edge\", entity = Profile.class, on = {})",
                FIELDS, PROFILE), "cover every basic id attribute exactly once");
    }

    @Test
    void ac_src_56_composite_id_pair_requires_an_explicit_id() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN, FIELDS, "@EmbeddedId Key key;"),
                "must be a basic id attribute");
    }

    @Test
    void ac_src_56_entity_must_be_a_jpa_entity() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("entity = Profile.class", "entity = String.class"),
                FIELDS, PROFILE), "entity must name a JPA @Entity");
    }

    @Test
    void ac_src_56_entity_must_not_be_a_primitive_class() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("entity = Profile.class", "entity = int.class"),
                FIELDS + ", @Column(entityJoin = \"manager\", attribute = \"id\") Long managerId", PROFILE),
                "entity must name a JPA @Entity");
    }

    @Test
    void ac_src_56_join_name_must_be_a_java_identifier() {
        rejected(model(SOURCE_JOIN + ENTITY_JOIN.replace("name = \"manager\"", "name = \"bad-name\""),
                FIELDS, PROFILE), "name must be a Java identifier");
    }
}
