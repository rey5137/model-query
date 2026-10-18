package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * A recursive source model: {@code @Depth} and {@code processor/30} R-PROC-37, {@code processor/32} MQ3037 and the
 * {@code @Depth} half of MQ3034, {@code processor/31} R-GEN-37.
 */
class RecursiveModelTest {

    private static final JavaFileObject FOLDER_ENTITY = source("tree.FolderEntity", """
            package tree;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class FolderEntity {
                @Id
                Long id;
                Long parentId;
                String name;
                Integer level;
            }
            """);

    /** The tree a recursive source walks: the seed's root id, its children and the level the CTE assigns a row. */
    private static final JavaFileObject FOLDER_NODE = source("tree.FolderNode", """
            package tree;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Depth;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;

            @SourceModel(root = FolderEntity.class)
            public record FolderNode(@PrimaryKey Long id, @Column(attribute = "parentId") Long parentId, String name,
                    @Depth Integer level) {}
            """);

    private static Compilation compileNode() {
        return compile(FOLDER_ENTITY, FOLDER_NODE);
    }

    /** {@link #FOLDER_NODE} with its fields written as {@code fields}. */
    private static Compilation compileNode(String fields) {
        return compile(FOLDER_ENTITY, source("tree.FolderNode", """
                package tree;

                import com.rey.modelquery.annotations.Aggregate;
                import com.rey.modelquery.annotations.AggregateFunction;
                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.Depth;
                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import com.rey.modelquery.annotations.Window;
                import com.rey.modelquery.annotations.WindowFunction;

                @SourceModel(root = FolderEntity.class)
                public record FolderNode(%s) {}
                """.formatted(fields)));
    }

    private static String message(DiagnosticCode code, String detail) {
        return code.code() + ": " + detail;
    }

    @Test
    void ac_proc_37_a_recursive_source_matches_its_golden_file() {
        Compilation compilation = compileNode();

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "tree.QFolderNode")).isEqualTo(resource("golden/QFolderNode.java"));
    }

    @Test
    void ac_proc_37_a_recursive_source_names_its_depth_field_in_source() {
        Compilation compilation = compileNode();

        assertThat(compilation).succeededWithoutWarnings();
        // R-GEN-37: the builder names the level column, and the generated source() requires the recursive step.
        assertThat(generatedFlat(compilation, "tree.QFolderNode")).contains(
                "public static SourceQuery.Builder<FolderEntity, FolderNode, FolderNode> source()",
                "return SourceQuery.builder(FolderNode.class, ROOT, ALL, KEY).depth(LEVEL);");
    }

    @Test
    void ac_proc_37_a_depth_that_is_not_an_integer_is_mq3037() {
        Compilation compilation = compileNode("@PrimaryKey Long id, String name, @Depth String level");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3037,
                "FolderNode.level: a recursive source takes one Integer @Depth"));
    }

    @Test
    void ac_proc_37_a_second_depth_is_mq3037() {
        Compilation compilation = compileNode("@PrimaryKey Long id, String name, @Depth Integer level, "
                + "@Depth Integer level2");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3037,
                "FolderNode.level2: a model declares one @Depth field, the level the CTE assigns its rows; the first "
                        + "is level"));
    }

    @Test
    void ac_proc_37_a_depth_outside_a_source_model_is_mq3037() {
        Compilation compilation = compile(FOLDER_ENTITY, source("tree.FolderView", """
                package tree;

                import com.rey.modelquery.annotations.Depth;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = FolderEntity.class)
                public record FolderView(@PrimaryKey Long id, String name, @Depth Integer level) {}
                """));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3037,
                "FolderView.level: @Depth is only allowed on a @SourceModel; only a source recurses"));
    }

    @Test
    void ac_proc_37_a_window_beside_a_depth_is_mq3037() {
        Compilation compilation = compileNode("@PrimaryKey Long id, String name, @Depth Integer level, "
                + "@Window(function = WindowFunction.ROW_NUMBER, orderBy = \"name\") Long rank");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3037,
                "FolderNode.rank: a recursive source takes no @Window; a database forbids one in the recursive "
                        + "term; declare it on a source over this source instead"));
    }

    @Test
    void ac_proc_37_an_aggregate_beside_a_depth_is_mq3037() {
        Compilation compilation = compileNode("@PrimaryKey @GroupBy Long id, @Depth Integer level, "
                + "@Aggregate(fn = AggregateFunction.COUNT) Long files");

        assertThat(compilation).failed();
        // The @GroupBy the aggregate needs is refused too: both are forbidden in the recursive term (R-PROC-37).
        assertThat(errors(compilation)).contains(
                message(DiagnosticCode.MQ3037, "FolderNode.files: a recursive source takes no @Aggregate; a database "
                        + "forbids one in the recursive term; declare it on a source over this source instead"),
                message(DiagnosticCode.MQ3037, "FolderNode.id: a recursive source takes no @GroupBy; a database "
                        + "forbids one in the recursive term; declare it on a source over this source instead"));
    }

    @Test
    void ac_proc_37_a_group_by_beside_a_depth_is_mq3037() {
        Compilation compilation = compileNode("@PrimaryKey @GroupBy Long id, @Depth Integer level, "
                + "@Aggregate(fn = AggregateFunction.COUNT) Long files");

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(message(DiagnosticCode.MQ3037,
                "FolderNode.id: a recursive source takes no @GroupBy; a database forbids one in the recursive term; "
                        + "declare it on a source over this source instead"));
    }

    @Test
    void ac_proc_34_a_depth_on_a_branch_is_mq3034() {
        // Only the branch recurses: the shape's own level column reads the entity's attribute (R-PROC-37).
        Compilation compilation = compile(FOLDER_ENTITY, shape("Integer level"), branch("@Depth Integer level"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3034,
                "FolderArchive.level: a branch declares no @Depth; a recursive model is neither a branch nor a "
                        + "shape"));
    }

    @Test
    void ac_proc_34_a_depth_on_a_shape_is_mq3034() {
        Compilation compilation = compile(FOLDER_ENTITY, shape("@Depth Integer level"), branch("Integer level"));

        assertThat(compilation).failed();
        assertThat(errors(compilation)).containsExactly(message(DiagnosticCode.MQ3034,
                "FolderArchive: the shape FolderFeed declares the @Depth field level, which no branch can recurse "
                        + "from; a recursive model is neither a branch nor a shape"));
    }

    private static JavaFileObject shape(String tail) {
        return source("tree.FolderFeed", """
                package tree;

                import com.rey.modelquery.annotations.Depth;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;

                @SourceModel(root = FolderEntity.class)
                public record FolderFeed(@PrimaryKey Long id, String name, %s) {}
                """.formatted(tail));
    }

    private static JavaFileObject branch(String tail) {
        return source("tree.FolderArchive", """
                package tree;

                import com.rey.modelquery.annotations.Depth;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;

                @SourceModel(root = FolderEntity.class, shape = FolderFeed.class)
                public record FolderArchive(@PrimaryKey Long id, String name, %s) {}
                """.formatted(tail));
    }
}
