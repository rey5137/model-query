package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.compileWithOptions;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.row;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectField;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** The generated vocabulary for reading a source as its own model (R-GEN-40, R-SRC-17). */
class SourceRowsModelTest {

    private static final JavaFileObject ITEM = source("shop.Item", """
            package shop;
            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity public class Item {
                @Id Long id;
                @Column(nullable = false) String name;
            }
            """);

    private static final JavaFileObject ROWS = source("shop.Rows", """
            package shop;
            import com.rey.modelquery.annotations.ExcludeFromDefaults;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.SourceModel;
            import com.rey.modelquery.annotations.Window;
            import com.rey.modelquery.annotations.WindowFunction;
            @SourceModel(root = Item.class)
            public record Rows(@PrimaryKey Long id, @ExcludeFromDefaults String name,
                    @Window(function = WindowFunction.ROW_NUMBER, orderBy = "id") Long rank) {}
            """);

    // AC-SRC-33
    @Test
    void ac_src_33_a_source_named_rows_has_a_fully_qualified_read_vocabulary() throws Exception {
        Compilation compilation = compile(ITEM, ROWS, source("shop.ReadRows", """
                package shop;
                import com.rey.modelquery.core.ModelQuery;
                public class ReadRows {
                    public static ModelQuery<Rows, Long, Rows> read() {
                        return QRows.query(QRows.source().build()).select(QRows.Rows.DEFAULT).build();
                    }
                }
                """));

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "shop.QRows")).isEqualTo(resource("golden/QRows.java"));
        ClassLoader loader = classes(compilation);
        Class<?> vocabulary = loader.loadClass("shop.QRows$Rows");
        for (var field : vocabulary.getDeclaredFields()) {
            assertThat(field.getModifiers()).isEqualTo(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL);
        }
        assertThat(vocabulary.getDeclaredConstructor().getModifiers()).isEqualTo(Modifier.PRIVATE);
        Object mapped = mapper(vocabulary).map(row(Map.of(field(vocabulary, "ID"), 7L,
                field(vocabulary, "NAME"), "item", field(vocabulary, "RANK"), 3L)));
        assertThat(mapped.getClass()).isEqualTo(loader.loadClass("shop.Rows"));
        assertThat(mapped.getClass().getMethod("id").invoke(mapped)).isEqualTo(7L);
        assertThat(mapped.getClass().getMethod("name").invoke(mapped)).isEqualTo("item");
        assertThat(mapped.getClass().getMethod("rank").invoke(mapped)).isEqualTo(3L);
        assertThat(loader.loadClass("shop.ReadRows").getMethod("read").invoke(null)).isNotNull();
    }

    // AC-SRC-33
    @Test
    void ac_src_33_a_class_mapper_reads_windows_and_preserves_unselected_initializers() throws Exception {
        Compilation compilation = compile(ITEM, source("shop.Bean", """
                package shop;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import com.rey.modelquery.annotations.Window;
                import com.rey.modelquery.annotations.WindowFunction;
                @SourceModel(root = Item.class)
                public class Bean {
                    @PrimaryKey private Long id;
                    private String name = "initial";
                    @Window(function = WindowFunction.ROW_NUMBER, orderBy = "id") private Long rank;
                    public Long getId() { return id; }
                    public void setId(Long value) { id = value; }
                    public String getName() { return name; }
                    public void setName(String value) { name = value; }
                    public Long getRank() { return rank; }
                    public void setRank(Long value) { rank = value; }
                }
                """));

        assertThat(compilation).succeededWithoutWarnings();
        Class<?> vocabulary = classes(compilation).loadClass("shop.QBean$Rows");
        Object mapped = mapper(vocabulary).map(row(Map.of(field(vocabulary, "ID"), 7L,
                field(vocabulary, "RANK"), 3L)));
        assertThat(mapped.getClass().getMethod("getId").invoke(mapped)).isEqualTo(7L);
        assertThat(mapped.getClass().getMethod("getName").invoke(mapped)).isEqualTo("initial");
        assertThat(mapped.getClass().getMethod("getRank").invoke(mapped)).isEqualTo(3L);
    }

    // AC-SRC-33
    @Test
    void ac_src_33_a_grouped_record_mapper_reads_aggregate_and_window_values() throws Exception {
        Compilation compilation = compile(ITEM, source("shop.Counts", """
                package shop;
                import com.rey.modelquery.annotations.Aggregate;
                import com.rey.modelquery.annotations.AggregateFunction;
                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.SourceModel;
                import com.rey.modelquery.annotations.Window;
                import com.rey.modelquery.annotations.WindowFunction;
                @SourceModel(root = Item.class)
                public record Counts(@PrimaryKey @GroupBy String name,
                        @Aggregate(fn = AggregateFunction.COUNT) Long count,
                        @Window(function = WindowFunction.ROW_NUMBER, orderBy = "name") Long rank) {}
                """));

        assertThat(compilation).succeededWithoutWarnings();
        Class<?> vocabulary = classes(compilation).loadClass("shop.QCounts$Rows");
        Object mapped = mapper(vocabulary).map(row(Map.of(field(vocabulary, "NAME"), "item",
                field(vocabulary, "COUNT"), 7L, field(vocabulary, "RANK"), 3L)));
        assertThat(mapped.getClass().getMethod("name").invoke(mapped)).isEqualTo("item");
        assertThat(mapped.getClass().getMethod("count").invoke(mapped)).isEqualTo(7L);
        assertThat(mapped.getClass().getMethod("rank").invoke(mapped)).isEqualTo(3L);
    }

    // AC-SRC-33
    @Test
    void ac_src_33_nullness_annotations_inside_rows_are_fully_qualified() {
        Compilation compilation = compileWithOptions(List.of("-Amodelquery.jspecify=true"), ITEM, ROWS);

        assertThat(compilation).succeededWithoutWarnings();
        String generated = generated(compilation, "shop.QRows");
        assertThat(generated.substring(generated.indexOf("public static final class Rows")))
                .contains("@org.jspecify.annotations.NullUnmarked")
                .doesNotContain("@NullUnmarked");
    }

    private static SelectField<?, ?> field(Class<?> vocabulary, String name) throws Exception {
        return (SelectField<?, ?>) vocabulary.getField(name).get(null);
    }

    private static RowMapper<?> mapper(Class<?> vocabulary) throws Exception {
        return (RowMapper<?>) vocabulary.getField("MAPPER").get(null);
    }
}
