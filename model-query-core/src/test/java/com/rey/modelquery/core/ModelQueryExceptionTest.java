package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** One catch clause for every library failure (spec reference/90 R-ERR-05, D-106). */
class ModelQueryExceptionTest {

    @Test
    void r_err_05_each_kind_is_caught_as_a_model_query_exception_and_reads_its_code() {
        IllegalStateException cause = new IllegalStateException("provider");
        List<Supplier<RuntimeException>> failures = List.of(
                () -> new ModelQueryDefinitionException(MqCode.MQ1001, "definition"),
                () -> new ModelQueryExecutionException(MqCode.MQ2001, "execution", cause),
                () -> new ModelQueryConfigurationException(MqCode.MQ4003, "configuration"),
                () -> new ChunkedWriteException("chunk", 0, null, List.of(), cause));

        List<MqCode> codes = failures.stream().map(ModelQueryExceptionTest::caughtCode).toList();

        assertThat(codes).containsExactly(MqCode.MQ1001, MqCode.MQ2001, MqCode.MQ4003, MqCode.MQ2502);
    }

    @Test
    void r_err_05_the_message_starts_with_the_code_and_the_cause_is_kept() {
        IllegalStateException cause = new IllegalStateException("provider");
        ModelQueryException e = new ModelQueryExecutionException(MqCode.MQ2001, "execution", cause);

        assertThat(e).hasMessage("MQ2001: execution").hasCause(cause);
    }

    private static MqCode caughtCode(Supplier<RuntimeException> failure) {
        try {
            throw failure.get();
        } catch (ModelQueryException e) {
            return e.code();
        }
    }
}
