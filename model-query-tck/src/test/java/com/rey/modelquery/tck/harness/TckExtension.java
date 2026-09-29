package com.rey.modelquery.tck.harness;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

/** Expands a {@link TckTest} into one invocation per selected target, starting its database lazily. */
final class TckExtension implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return true;
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        return TckTarget.selected().stream().map(Invocation::new);
    }

    private record Invocation(TckTarget target) implements TestTemplateInvocationContext {

        @Override
        public String getDisplayName(int invocationIndex) {
            return "[" + target.displayName() + "]";
        }

        @Override
        public List<Extension> getAdditionalExtensions() {
            return List.of(new ParameterResolver() {
                @Override
                public boolean supportsParameter(ParameterContext p, ExtensionContext e) {
                    return p.getParameter().getType() == TckDatabase.class;
                }

                @Override
                public Object resolveParameter(ParameterContext p, ExtensionContext e) {
                    return TckDatabases.get(target);
                }
            });
        }
    }
}
