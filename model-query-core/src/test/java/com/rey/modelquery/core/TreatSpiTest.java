package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TreatSpiTest {

    @Test
    void ac_vnd_16_sites_copy_their_uses_and_allow_an_empty_set() {
        var uses = EnumSet.of(TreatUse.SELECT, TreatUse.LEFT_JOIN);
        var site = new TreatSite(Object.class, String.class, Optional.of("payment"), StatementShape.CONTENT,
                false, uses);
        uses.clear();
        assertThat(site.uses()).containsExactly(TreatUse.SELECT, TreatUse.LEFT_JOIN);
        assertThatThrownBy(() -> site.uses().add(TreatUse.ORDER)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new TreatSite(Object.class, String.class, Optional.empty(), StatementShape.WRITE,
                true, Set.of()).uses()).isEmpty();
    }

    @Test
    void ac_vnd_16_render_options_are_immutable_and_keep_the_treat_renderer_in_every_copy() {
        TreatRenderer renderer = new TreatRenderer() {
            @Override
            public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
                throw new AssertionError("not rendered in this test");
            }

            @Override
            public Optional<String> refusal(TreatSite site) {
                return Optional.empty();
            }

            @Override
            public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement,
                    StatementShape shape) {
                return Optional.empty();
            }
        };
        RenderOptions original = RenderOptions.of(31, 97);
        RenderOptions options = original.withTreatRenderer(renderer);
        assertThat(original.treatRenderer()).isEmpty();
        assertThat(RenderOptions.portable().treatRenderer()).isEmpty();
        assertThat(options.maxInListSize()).isEqualTo(31);
        assertThat(options.maxBindParameters()).isEqualTo(97);
        assertThat(options.withNullPrecedenceRenderer(stub(NullPrecedenceRenderer.class)).treatRenderer())
                .containsSame(renderer);
        assertThat(options.withConstantRenderer(stub(ConstantRenderer.class)).treatRenderer()).containsSame(renderer);
        assertThat(options.withSequenceRenderer(stub(SequenceRenderer.class)).treatRenderer()).containsSame(renderer);
        assertThat(options.withSourceRenderer(stub(SourceRenderer.class)).treatRenderer()).containsSame(renderer);
        assertThat(options.withSubqueryRenderer(stub(SubqueryRenderer.class)).treatRenderer()).containsSame(renderer);
        assertThat(options.withConstantRenderer(stub(ConstantRenderer.class)).withTreatRenderer(renderer)
                .constantRenderer()).isPresent();
    }

    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> { throw new AssertionError(method.getName()); }));
    }
}
