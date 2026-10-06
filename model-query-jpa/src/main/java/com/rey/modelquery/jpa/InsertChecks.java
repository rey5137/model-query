package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.IdGeneration;
import com.rey.modelquery.jpa.spi.InsertTarget;
import java.util.List;
import java.util.Set;

/**
 * The checks of a bulk insert against what the provider reports of its root: the generator allowlist of api/14
 * R-WRT-26, the mappings no bulk insert writes, the id the model names, and a conflict clause's key, action and
 * {@code where}. Run on the definition's first execution, before any statement (D-61, D-116, D-117).
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
     * Checks an insert's conflict clause, detected on {@code keys}, against the {@code uniqueKeys} the mapping
     * declares and whether the provider renders the clause's action (R-WRT-34).
     *
     * @throws ModelQueryDefinitionException {@code MQ1804} for keys that are not exactly one declared unique key, or a
     *     {@code doNothing()} the provider does not render
     */
    static void checkConflict(Object insert, List<String> keys, List<Set<String>> uniqueKeys, boolean rendered) {
        if (!uniqueKeys.contains(Set.copyOf(keys))) {
            throw new ModelQueryDefinitionException(MqCode.MQ1804, insert + ": onConflict" + keys + " names no "
                    + "unique key of the mapping, which declares " + uniqueKeys + "; declare it with @Id, "
                    + "@NaturalId, @Column(unique = true), @JoinColumn(unique = true) or @Table(uniqueConstraints), "
                    + "since a unique index only a migration or orm.xml declares is not seen");
        }
        if (!rendered) {
            throw new ModelQueryDefinitionException(MqCode.MQ1804, insert + ": the persistence provider does not "
                    + "render doNothing() for this database and would write a plain insert, which fails on the first "
                    + "conflicting row; use doUpdate(...), or a provider version that renders it");
        }
    }

    /**
     * Checks an insert's conflict clause on {@code keys} given whether the vendor honours the named key or the insert
     * accepts any unique key (R-WRT-36).
     *
     * @throws ModelQueryDefinitionException {@code MQ1804} for a vendor that detects a conflict on any unique key
     *     without {@code anyUniqueKey()}
     */
    static void checkConflictTarget(Object insert, List<String> keys, boolean keyHonoured) {
        if (!keyHonoured) {
            throw new ModelQueryDefinitionException(MqCode.MQ1804, insert + ": this database detects a conflict on "
                    + "any unique key, not only " + keys + ", so a row could skip or update a row it matches on "
                    + "another key; anyUniqueKey() accepts that and the vendor's count");
        }
    }

    /**
     * Checks a conflict update whose {@code where} reads the {@code assigned} columns the update also assigns, given
     * whether the executor's configuration {@code allows} two or more and whether the vendor's {@code where}
     * {@code seesEarlierAssignments} (R-WRT-34, D-117).
     *
     * @throws ModelQueryDefinitionException {@code MQ1804} for two or more such columns unless allowed, and where the
     *     vendor's {@code where} reads the values earlier assignments wrote even then
     */
    static void checkConflictWhere(Object insert, List<String> assigned, boolean allows,
            boolean seesEarlierAssignments) {
        if (assigned.size() < 2) {
            return;
        }
        if (!allows) {
            throw new ModelQueryDefinitionException(MqCode.MQ1804, insert + ": the doUpdate(...) where reads "
                    + assigned + ", which the update also assigns; on some databases it would filter on the values "
                    + "the earlier assignments wrote. Filter on at most one of them, or set "
                    + "ModelQueryConfig.conflictUpdateWhereOnAssignedColumns(true) "
                    + "(modelquery.bulk-write.conflict-update-where-on-assigned-columns) where the database reads "
                    + "the stored row");
        }
        if (seesEarlierAssignments) {
            throw new ModelQueryDefinitionException(MqCode.MQ1804, insert + ": the doUpdate(...) where reads "
                    + assigned + ", which the update also assigns, and this database evaluates it per assignment, "
                    + "after the earlier assignments, so it would filter on the values they wrote; filter on at most "
                    + "one of them");
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
