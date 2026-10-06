package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.IdGeneration;
import com.rey.modelquery.jpa.spi.InsertTarget;

/**
 * The checks of a bulk insert against what the provider reports of its root: the generator allowlist of api/14
 * R-WRT-26, the mappings no bulk insert writes, and the id the model names. Run on the definition's first execution,
 * before any statement (D-61, D-116, D-117).
 */
final class InsertChecks {

    private InsertChecks() {
    }

    /**
     * Checks an insert of {@code insert}, an insert-select when {@code select}, against {@code target}, given whether
     * the insert {@code writesId}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1805} for a mapping the target reports unsupported or a
     *     generator outside the form's allowlist; {@code MQ1802} for an id the model names against its generator
     */
    static void checkTarget(Object insert, InsertTarget target, boolean select, boolean writesId) {
        if (!target.unsupportedMappings().isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1805, insert + ": the root is mapped with "
                    + String.join(", ", target.unsupportedMappings()) + ", which a bulk insert cannot write; "
                    + "persist(...) writes one row through JPA");
        }
        String refused = refusal(target.id(), select);
        if (refused != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1805, insert + ": the root's id has " + refused);
        }
        boolean assigned = target.id() instanceof IdGeneration.Assigned;
        if (assigned && !writesId) {
            throw new ModelQueryDefinitionException(MqCode.MQ1802, insert + ": the root's id has no generator, and "
                    + "the insert does not write it; name it in the model with @PrimaryKey"
                    + (select ? " and map it from the source" : ""));
        }
        if (!assigned && writesId) {
            throw new ModelQueryDefinitionException(MqCode.MQ1802, insert + ": writes the root's id, which "
                    + describe(target.id()) + " generates; leave it out of the model, since an explicit value would "
                    + "not advance the generator and a later generated key could collide");
        }
    }

    /**
     * Checks that {@code id}'s keys are drawn before the statement, so {@code insertReturningKeys} can return them
     * (R-WRT-33).
     *
     * @throws ModelQueryDefinitionException {@code MQ1807} for an {@code IDENTITY} or assigned id
     */
    static void checkKeysGenerated(Object insert, IdGeneration id) {
        if (id instanceof IdGeneration.Identity) {
            throw new ModelQueryDefinitionException(MqCode.MQ1807, insert + ": insertReturningKeys(...) on an "
                    + "IDENTITY id, whose keys only the database knows after each row; persist(...) returns one "
                    + "row's key, and insert(...) writes the rows");
        }
        if (id instanceof IdGeneration.Assigned) {
            throw new ModelQueryDefinitionException(MqCode.MQ1807, insert + ": insertReturningKeys(...) on an "
                    + "assigned id, so the keys are the rows' own ids; read them from the rows and use insert(...)");
        }
    }

    /**
     * Whether an insert-values draws {@code id}'s keys from the generator before its statement and writes them as
     * values: for a sequence, a table or a UUID generator (R-WRT-26).
     */
    static boolean drawsKeys(IdGeneration id) {
        return id instanceof IdGeneration.Sequence || id instanceof IdGeneration.Table
                || id instanceof IdGeneration.Uuid;
    }

    /** Why the form refuses {@code id}, or {@code null} when it takes it (R-WRT-26). */
    private static String refusal(IdGeneration id, boolean select) {
        if (id instanceof IdGeneration.Other) {
            return describe(id) + ", which no bulk insert supports";
        }
        if (!select || id instanceof IdGeneration.Assigned || id instanceof IdGeneration.Identity) {
            return null;
        }
        if (id instanceof IdGeneration.Sequence sequence) {
            return sequence.physical() && sequence.increment() == 1 ? null : describe(id) + ", whose keys an "
                    + "insert-select cannot write in one statement; only a database sequence with increment 1 renders "
                    + "inline";
        }
        return describe(id) + ", which an insert-select cannot draw from";
    }

    private static String describe(IdGeneration id) {
        if (id instanceof IdGeneration.Assigned) {
            return "no generator";
        } else if (id instanceof IdGeneration.Identity) {
            return "an IDENTITY column";
        } else if (id instanceof IdGeneration.Sequence sequence) {
            return (sequence.physical() ? "a sequence" : "a table-backed sequence") + " with increment "
                    + sequence.increment();
        } else if (id instanceof IdGeneration.Table) {
            return "a table generator";
        } else if (id instanceof IdGeneration.Uuid) {
            return "a UUID generator";
        }
        return "the generator " + ((IdGeneration.Other) id).generatorClass();
    }
}
