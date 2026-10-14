package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ForeignKeyLookup.Answer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The foreign-key check of a write (R-COL-29, D-136): every to-one a statement read by its id is put to the
 * executor's lookup by owning entity and attribute, and refused {@code MQ1004} unless it is a local column. Only the
 * check is here; the reads a statement makes are the TCK's.
 */
class ForeignKeyCheckTest {

    record Model(Long id, Long child) {}

    static class Parent {}

    static class Sibling {}

    private static final ColumnField<Model, Parent, Long> PARENT_CHILD =
            ColumnField.of(Model.class, TableField.root(Parent.class), "child", Long.class);
    private static final ColumnField<Model, Sibling, Long> SIBLING_CHILD =
            ColumnField.of(Model.class, TableField.root(Sibling.class), "child", Long.class);

    private static JoinContext reading() {
        return JoinContext.rootless(null, RenderOptions.portable());
    }

    @Test
    void ac_col_34_a_local_column_answer_accepts_every_read() {
        JoinContext ctx = reading();
        ctx.readByForeignKey(PARENT_CHILD, Parent.class);
        ctx.readByForeignKey(SIBLING_CHILD, Sibling.class);

        assertThatCode(() -> WriteRendering.checkForeignKeys(ctx, (entity, toOne) -> Answer.LOCAL_COLUMN))
                .doesNotThrowAnyException();
    }

    @Test
    void ac_col_34_a_joining_answer_is_mq1004_naming_the_owner_and_the_column() {
        JoinContext ctx = reading();
        ctx.readByForeignKey(PARENT_CHILD, Parent.class);

        assertThatThrownBy(() -> WriteRendering.checkForeignKeys(ctx, (entity, toOne) -> Answer.JOINS))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1004))
                .hasMessageContaining("Parent.child").hasMessageContaining("the provider reports that it is not")
                .hasMessageNotContaining("cannot tell");
    }

    @Test
    void ac_col_34_an_unknown_answer_is_mq1004_saying_the_provider_cannot_tell() {
        JoinContext ctx = reading();
        ctx.readByForeignKey(PARENT_CHILD, Parent.class);

        assertThatThrownBy(() -> WriteRendering.checkForeignKeys(ctx, (entity, toOne) -> Answer.UNKNOWN))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1004))
                .hasMessageContaining("Parent.child").hasMessageContaining("the provider cannot tell");
    }

    @Test
    void ac_col_34_a_read_of_one_attribute_on_two_entities_asks_about_both() {
        JoinContext ctx = reading();
        ctx.readByForeignKey(PARENT_CHILD, Parent.class);
        ctx.readByForeignKey(PARENT_CHILD, Parent.class); // the same read twice asks once
        ctx.readByForeignKey(SIBLING_CHILD, Sibling.class);
        var asked = new ArrayList<String>();

        WriteRendering.checkForeignKeys(ctx, (entity, toOne) -> {
            asked.add(entity.getSimpleName() + "." + toOne);
            return Answer.LOCAL_COLUMN;
        });

        assertThat(asked).containsExactly("Parent.child", "Sibling.child");
    }

    @Test
    void ac_col_34_the_second_of_two_owners_is_refused_when_only_it_joins() {
        JoinContext ctx = reading();
        ctx.readByForeignKey(PARENT_CHILD, Parent.class);
        ctx.readByForeignKey(SIBLING_CHILD, Sibling.class);
        List<Class<?>> joining = List.of(Sibling.class);

        assertThatThrownBy(() -> WriteRendering.checkForeignKeys(ctx,
                (entity, toOne) -> joining.contains(entity) ? Answer.JOINS : Answer.LOCAL_COLUMN))
                .hasMessageContaining("Sibling.child");
    }

    @Test
    void ac_col_34_a_scratch_sub_query_context_hands_its_reads_to_the_statement() {
        JoinContext statement = reading();
        JoinContext sub = reading();
        sub.readByForeignKey(SIBLING_CHILD, Sibling.class);

        statement.adoptForeignKeyReads(sub);

        assertThat(statement.foreignKeyReads()).hasSize(1);
        assertThatThrownBy(() -> WriteRendering.checkForeignKeys(statement, (entity, toOne) -> Answer.UNKNOWN))
                .hasMessageContaining("Sibling.child");
    }
}
