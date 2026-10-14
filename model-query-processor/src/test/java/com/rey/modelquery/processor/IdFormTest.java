package com.rey.modelquery.processor;

import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.resource;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import java.util.Locale;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * A query model reads a to-one by its id ({@code processor/30} R-PROC-33, {@code processor/31} R-GEN-19, R-GEN-21;
 * D-136).
 */
class IdFormTest {

    private static final JavaFileObject JOIN_FORMULA = source("org.hibernate.annotations.JoinFormula", """
            package org.hibernate.annotations;

            public @interface JoinFormula {
                String value();
            }
            """);

    private static final JavaFileObject JOIN_COLUMN_OR_FORMULA = source(
            "org.hibernate.annotations.JoinColumnOrFormula", """
            package org.hibernate.annotations;

            public @interface JoinColumnOrFormula {
                JoinFormula formula();
            }
            """);

    private static final JavaFileObject NOT_FOUND = source("org.hibernate.annotations.NotFound", """
            package org.hibernate.annotations;

            public @interface NotFound {}
            """);

    private static final JavaFileObject CUSTOMER = source("idf.CustomerEntity", """
            package idf;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class CustomerEntity {
                @Id
                Long id;
                String name;
            }
            """);

    private static final JavaFileObject COMPOSITE = source("idf.CompositeEntity", """
            package idf;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.IdClass;

            @Entity
            @IdClass(CompositeId.class)
            public class CompositeEntity {
                @Id
                Long a;
                @Id
                Long b;
            }
            """);

    private static final JavaFileObject COMPOSITE_ID = source("idf.CompositeId", """
            package idf;

            public class CompositeId {
                Long a;
                Long b;
            }
            """);

    private static final JavaFileObject EMBEDDED_ID = source("idf.EmbeddedIdEntity", """
            package idf;

            import jakarta.persistence.Embeddable;
            import jakarta.persistence.EmbeddedId;
            import jakarta.persistence.Entity;

            @Entity
            public class EmbeddedIdEntity {
                @EmbeddedId
                Key id;

                @Embeddable
                public static class Key {
                    Long a;
                    Long b;
                }
            }
            """);

    private static final JavaFileObject DERIVED = source("idf.DerivedEntity", """
            package idf;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.MapsId;
            import jakarta.persistence.OneToOne;

            @Entity
            public class DerivedEntity {
                @Id
                Long id;
                String note;
                @OneToOne
                @MapsId
                CustomerEntity owner;
            }
            """);

    private static final JavaFileObject SHADOW = source("idf.ShadowEntity", """
            package idf;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.MapsId;
            import jakarta.persistence.OneToOne;

            @Entity
            public class ShadowEntity {
                @Id
                Long id;
                String note;
                @OneToOne
                @MapsId
                CustomerEntity owner;
            }
            """);

    private static final JavaFileObject REGION = source("idf.RegionEntity", """
            package idf;

            import jakarta.persistence.Embeddable;
            import jakarta.persistence.ManyToOne;

            @Embeddable
            public class RegionEntity {
                @ManyToOne
                CustomerEntity manager;
            }
            """);

    /** One to-one per mapping R-PROC-33 reads or refuses. */
    private static final JavaFileObject TICKET = source("idf.TicketEntity", """
            package idf;

            import jakarta.persistence.Embedded;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.JoinColumn;
            import jakarta.persistence.JoinColumns;
            import jakarta.persistence.ManyToOne;
            import jakarta.persistence.OneToOne;
            import org.hibernate.annotations.JoinColumnOrFormula;
            import org.hibernate.annotations.JoinFormula;
            import org.hibernate.annotations.NotFound;

            @Entity
            public class TicketEntity {
                @Id
                Long id;
                String title;
                @ManyToOne
                CustomerEntity referrer;
                @ManyToOne
                CustomerEntity buyer;
                @OneToOne(mappedBy = "ticket")
                CustomerEntity inverse;
                @ManyToOne
                @JoinColumn(name = "sku", referencedColumnName = "code")
                CustomerEntity bySku;
                @ManyToOne
                @JoinColumns({@JoinColumn(name = "x"), @JoinColumn(name = "y")})
                CustomerEntity pair;
                @ManyToOne
                @JoinFormula("1")
                CustomerEntity formula;
                @ManyToOne
                @JoinColumnOrFormula(formula = @JoinFormula("1"))
                CustomerEntity either;
                @ManyToOne
                @NotFound
                CustomerEntity missing;
                @ManyToOne
                DerivedEntity derived;
                @ManyToOne
                CompositeEntity composite;
                @ManyToOne
                EmbeddedIdEntity embedded;
                @Embedded
                RegionEntity region;
            }
            """);

    private static final JavaFileObject ID_CONVERTER = source("idf.CustomerIdConverter", """
            package idf;

            import com.rey.modelquery.core.ColumnConverter;

            public final class CustomerIdConverter implements ColumnConverter<String, Long> {
                public static final CustomerIdConverter INSTANCE = new CustomerIdConverter();

                @Override
                public String toModel(Long attribute) {
                    return attribute == null ? null : "C" + attribute;
                }

                @Override
                public Long toAttribute(String model) {
                    return model == null ? null : Long.valueOf(model.substring(1));
                }
            }
            """);

    private static final JavaFileObject WRONG_CONVERTER = source("idf.WrongConverter", """
            package idf;

            import com.rey.modelquery.core.ColumnConverter;

            public final class WrongConverter implements ColumnConverter<String, Integer> {
                public static final WrongConverter INSTANCE = new WrongConverter();

                @Override
                public String toModel(Integer attribute) {
                    return null;
                }

                @Override
                public Integer toAttribute(String model) {
                    return null;
                }
            }
            """);

    private static final JavaFileObject[] ENTITIES = {
        JOIN_FORMULA, JOIN_COLUMN_OR_FORMULA, NOT_FOUND, CUSTOMER, COMPOSITE, COMPOSITE_ID, EMBEDDED_ID, DERIVED,
        REGION, TICKET, ID_CONVERTER, WRONG_CONVERTER};

    private static JavaFileObject model(String annotation, String fields) {
        return source("idf.TicketRow", """
                package idf;

                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.GroupBy;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                %s
                public record TicketRow(@PrimaryKey Long id, %s) {}
                """.formatted(annotation, fields));
    }

    private static Compilation compileModel(String annotation, String fields) {
        JavaFileObject[] sources = java.util.Arrays.copyOf(ENTITIES, ENTITIES.length + 1);
        sources[ENTITIES.length] = model(annotation, fields);
        return compile(sources);
    }

    private static Compilation compileModel(String fields) {
        return compileModel("@QueryModel(root = TicketEntity.class)", fields);
    }

    private static String idForm(String attribute, String type) {
        return "@Column(attribute = \"" + attribute + "\") " + type + " " + attribute + "Id";
    }

    // ---- AC-PROC-28

    @Test
    void ac_proc_28_the_id_form_and_a_converter_from_the_id_type_are_accepted() {
        Compilation compilation = compileModel(idForm("referrer", "Long") + ", "
                + "@Column(attribute = \"buyer\", converter = CustomerIdConverter.class) String buyerRef");

        assertThat(errors(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(generated(compilation, "idf.QTicketRow").replaceAll("\\s+", " "))
                .contains("REFERRER_ID = ColumnField.of(TicketRow.class, ROOT, \"referrer\", Long.class)")
                .contains("BUYER_REF = ColumnField.of(TicketRow.class, ROOT, \"buyer\", String.class, Long.class, "
                        + "CustomerIdConverter.INSTANCE)");
    }

    private void refused(String attribute, String reason) {
        Compilation compilation = compileModel(idForm(attribute, "Long"));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation)).singleElement().asString()
                .startsWith("MQ3002: TicketRow." + attribute + "Id: '" + attribute + "' can't be read by its id: ")
                .contains(reason);
    }

    @Test
    void ac_proc_28_the_inverse_side_is_refused_with_its_reason() {
        refused("inverse", "the inverse side (mappedBy = \"ticket\")");
    }

    @Test
    void ac_proc_28_a_non_primary_key_referenced_column_is_refused_with_its_reason() {
        refused("bySku", "referencedColumnName = \"code\"");
    }

    @Test
    void ac_proc_28_join_columns_are_refused_with_their_reason() {
        refused("pair", "@JoinColumns, a composite join");
    }

    @Test
    void ac_proc_28_a_join_formula_is_refused_with_its_reason() {
        refused("formula", "@JoinFormula, which joins on a formula");
    }

    @Test
    void ac_proc_28_a_join_column_or_formula_is_refused_with_its_reason() {
        refused("either", "@JoinColumnOrFormula, which joins on a formula");
    }

    @Test
    void ac_proc_28_not_found_is_refused_with_its_reason() {
        refused("missing", "@NotFound");
    }

    @Test
    void ac_proc_28_a_derived_identity_target_is_refused_with_its_reason() {
        refused("derived", "DerivedEntity's id is derived from an association (@MapsId)");
    }

    @Test
    void ac_proc_28_an_id_class_target_is_refused_with_its_reason() {
        refused("composite", "CompositeEntity has @IdClass");
    }

    @Test
    void ac_proc_28_an_embedded_id_target_is_refused_with_its_reason() {
        refused("embedded", "EmbeddedIdEntity has an @EmbeddedId");
    }

    @Test
    void ac_proc_28_a_to_one_inside_an_embedded_value_is_refused_with_its_reason() {
        Compilation compilation = compileModel("@Column(attribute = \"region.manager\") Long managerId");

        assertThat(errors(compilation)).singleElement().asString()
                .isEqualTo("MQ3002: TicketRow.managerId: 'region.manager' can't be read by its id: it is inside an "
                        + "embedded value");
    }

    @Test
    void ac_proc_28_another_type_is_refused_naming_the_targets_id_type() {
        Compilation compilation = compileModel(idForm("referrer", "String"));

        assertThat(errors(compilation)).containsExactly("MQ3002: TicketRow.referrerId: model type String, entity "
                + "attribute type CustomerEntity; a column of a to-one takes CustomerEntity's id type Long to read "
                + "its foreign key");
    }

    @Test
    void ac_proc_28_the_dotted_id_path_stays_unknown() {
        Compilation compilation = compileModel("@Column(attribute = \"referrer.id\") Long referrerId");

        assertThat(errors(compilation)).singleElement().asString()
                .startsWith("MQ3001: TicketRow.referrerId: ").contains("is an association");
    }

    @Test
    void ac_proc_28_a_converter_must_convert_the_id_type_as_core_requires() {
        Compilation compilation = compileModel(
                "@Column(attribute = \"referrer\", converter = WrongConverter.class) String referrerRef");

        assertThat(errors(compilation)).singleElement().asString()
                .startsWith("MQ3014: TicketRow.referrerRef: WrongConverter converts String to Integer, ")
                .contains("entity attribute type Long");
    }

    @Test
    void ac_proc_28_a_converter_over_the_whole_entity_stays_the_whole_entity_form() {
        Compilation compilation = compileModel(
                "@Column(attribute = \"referrer\", converter = CustomerIdConverter.class) String referrerRef");

        assertThat(errors(compilation)).isEmpty();
    }

    @Test
    void ac_proc_28_mq3016_names_the_id_form() {
        Compilation compilation = compileModel("idf.CustomerEntity referrer");

        assertThat(compilation.warnings().stream().map(warning -> warning.getMessage(Locale.ROOT)))
                .containsExactly("MQ3016: TicketRow.referrer: selects the whole CustomerEntity entity; use @Join "
                        + "with a query model of CustomerEntity to select only its columns, or @Column(attribute = "
                        + "\"referrer\") Long referrerId to read its id");
    }

    @Test
    void ac_proc_28_mq3016_does_not_suggest_an_id_form_the_mapping_refuses() {
        Compilation compilation = compileModel("idf.CustomerEntity missing");

        assertThat(compilation.warnings().stream().map(warning -> warning.getMessage(Locale.ROOT)))
                .containsExactly("MQ3016: TicketRow.missing: selects the whole CustomerEntity entity; use @Join "
                        + "with a query model of CustomerEntity to select only its columns");
    }

    @Test
    void ac_proc_28_group_by_on_a_whole_entity_to_one_is_mq3028_naming_the_id_form() {
        Compilation compilation = compileModel("@GroupBy idf.CustomerEntity referrer");

        assertThat(errors(compilation)).containsExactly("MQ3028: TicketRow.referrer: @GroupBy on a whole "
                + "CustomerEntity entity: the database groups by its foreign key but the select lists every column "
                + "of the entity; group by its id with @Column(attribute = \"referrer\") Long referrerId");
    }

    @Test
    void ac_proc_28_group_by_on_the_id_form_is_accepted() {
        Compilation compilation = compileModel("@GroupBy " + idForm("referrer", "Long"));

        assertThat(errors(compilation)).isEmpty();
    }

    // ---- AC-GEN-33

    private static Compilation generating() {
        return compileModel("@QueryModel(root = TicketEntity.class, generateChanges = true, generateInserts = true)",
                "String title, " + idForm("referrer", "Long") + ", idf.CustomerEntity buyer, "
                        + "@Column(attribute = \"buyer\", converter = CustomerIdConverter.class) String buyerRef");
    }

    @Test
    void ac_gen_33_the_id_form_is_written_by_the_change_set_the_insert_and_copied_by_from() {
        Compilation compilation = generating();

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(generated(compilation, "idf.TicketRowChanges"))
                .isEqualTo(resource("golden/IdFormRowChanges.java"));
        assertThat(generated(compilation, "idf.QTicketRow")).isEqualTo(resource("golden/QIdFormRow.java"));
    }

    @Test
    void ac_gen_33_the_whole_entity_form_stays_left_out_of_both() {
        Compilation compilation = generating();

        assertThat(generated(compilation, "idf.TicketRowChanges"))
                .doesNotContain(" buyer(").contains("<li>{@code buyer}: a to-one; only an {@code @UpdateModel}");
        assertThat(generated(compilation, "idf.QTicketRow"))
                .contains("<li>{@code buyer}: a to-one; only an {@code @InsertModel}");
    }

    @Test
    void ac_gen_33_the_id_form_over_a_maps_id_to_one_is_left_out_of_the_insert() {
        JavaFileObject shadow = source("idf.ShadowRow", """
                package idf;

                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = ShadowEntity.class, generateInserts = true)
                public record ShadowRow(
                        @PrimaryKey Long id, @Column(attribute = "owner") Long ownerId) {}
                """);
        Compilation compilation = compile(CUSTOMER, SHADOW, shadow);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(generated(compilation, "idf.QShadowRow"))
                .contains("<li>{@code ownerId}: a to-one {@code @MapsId}, whose foreign key is the id column");
    }

    @Test
    void ac_gen_33_the_id_form_over_a_maps_id_to_one_is_left_out_of_the_change_set() {
        JavaFileObject shadow = source("idf.ShadowRow", """
                package idf;

                import com.rey.modelquery.annotations.Column;
                import com.rey.modelquery.annotations.PrimaryKey;
                import com.rey.modelquery.annotations.QueryModel;

                @QueryModel(root = ShadowEntity.class, generateChanges = true)
                public record ShadowRow(
                        @PrimaryKey Long id, String note, @Column(attribute = "owner") Long ownerId) {}
                """);
        Compilation compilation = compile(CUSTOMER, SHADOW, shadow);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(generated(compilation, "idf.ShadowRowChanges"))
                .doesNotContain(" ownerId(")
                .contains("<li>{@code ownerId}: a to-one {@code @MapsId}, whose foreign key is the id column");
    }
}
