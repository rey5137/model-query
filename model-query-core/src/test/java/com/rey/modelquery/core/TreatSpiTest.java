package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
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
    void ac_vnd_21_an_expected_narrowing_names_subtypes_of_its_entity_and_keeps_its_own_copy() {
        var kind = ExpectedNarrowing.Kind.EXISTS_ROOT;
        var types = new LinkedHashSet<Class<?>>(List.of(Integer.class, Long.class));
        var narrowing = new ExpectedNarrowing(kind, Number.class, types);
        types.clear();
        assertThat(narrowing.types()).containsExactly(Integer.class, Long.class);
        assertThatThrownBy(() -> narrowing.types().add(Short.class))
                .isInstanceOf(UnsupportedOperationException.class);
        // Equal narrowings stay equal, so a statement's list compares as a multiset.
        assertThat(new ExpectedNarrowing(kind, Number.class, Set.of(Long.class, Integer.class))).isEqualTo(narrowing);
        assertThatThrownBy(() -> new ExpectedNarrowing(null, Number.class, Set.of(Long.class)))
                .isExactlyInstanceOf(NullPointerException.class).hasMessage("kind");
        assertThatThrownBy(() -> new ExpectedNarrowing(kind, null, Set.of(Long.class)))
                .isExactlyInstanceOf(NullPointerException.class).hasMessage("entity");
        assertThatThrownBy(() -> new ExpectedNarrowing(kind, Number.class, null))
                .isExactlyInstanceOf(NullPointerException.class).hasMessage("types");
        assertThatThrownBy(() -> new ExpectedNarrowing(kind, Number.class, Collections.singleton(null)))
                .isExactlyInstanceOf(NullPointerException.class).hasMessage("type");
        assertThatThrownBy(() -> new ExpectedNarrowing(kind, Number.class, Set.of()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("an expected narrowing of Number names no subtype");
        assertThatThrownBy(() -> new ExpectedNarrowing(kind, Number.class, Set.of(Long.class, String.class)))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("String is not a subtype of Number");
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
                    StatementShape shape, List<ExpectedNarrowing> expected) {
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
