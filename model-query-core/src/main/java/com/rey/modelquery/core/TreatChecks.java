package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Definition-only refusal before any statement when the factory has no treat renderer (R-COL-34, AC-VND-16). */
@EngineFacing
@Incubating
public final class TreatChecks {

    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> checked = Collections.newSetFromMap(new IdentityHashMap<>());

    private TreatChecks() {}

    /**
     * Checks a read or write definition and returns every successfully scanned definition, including fetch children,
     * for the executor's cache (INV-9, AC-VND-16). A failed scan returns no verdicts.
     */
    @Incubating
    public static Set<Object> check(Object definition) {
        var checks = new TreatChecks();
        checks.definition(Objects.requireNonNull(definition, "definition"));
        return Set.copyOf(checks.checked);
    }

    void definition(Object definition) {
        if (!seen.add(definition)) {
            return;
        }
        if (definition instanceof ModelQuery<?, ?, ?> query) {
            query.checkTreats(this);
        } else if (definition instanceof ModelUpdate<?, ?> update) {
            update.checkTreats(this);
        } else if (definition instanceof ModelDelete<?, ?> delete) {
            delete.checkTreats(this);
        } else if (definition instanceof ModelInsert<?, ?> insert) {
            insert.checkTreats(this);
        } else if (definition instanceof ModelPersist<?, ?, ?> persist) {
            persist.checkTreats(this);
        } else {
            throw new IllegalArgumentException("Not a query or write definition: " + definition.getClass().getName());
        }
        checked.add(definition);
    }

    void field(SelectField<?, ?> field) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            table(column.table().key());
            column.liftedFrom().ifPresent(this::field);
        } else if (field instanceof ExpressionField<?, ?> expression) {
            expression.checkTreats(this);
        } else if (field instanceof AggregateField<?, ?> aggregate) {
            aggregate.checkTreats(this);
        } else if (field instanceof WindowField<?, ?> window) {
            window.checkTreats(this);
        }
    }

    void table(JoinKey key) {
        for (; key != null && key.parent() != null; key = key.parent()) {
            if (key.type() == null) { // Only a downcast has a parent and no join type.
                throw new ModelQueryConfigurationException(MqCode.MQ4014, key.attribute()
                        + ": a treated path needs the provider's TreatRenderer (ProviderSupport#treats(emf))");
            }
        }
    }

    void condition(Condition condition) {
        condition.column().ifPresent(this::field);
        condition.right().ifPresent(this::field);
        condition.operands().forEach(this::field);
        condition.path().ifPresent(path -> table(path.key()));
        condition.children().forEach(this::condition);
        condition.subSelect().ifPresent(this::subSelect);
    }

    void subSelect(SubSelect<?, ?> sub) {
        field(sub.column());
        sub.conditions().forEach(this::condition);
    }

    void fetch(FetchPlan<?> plan) {
        if (plan == null || !seen.add(plan)) {
            return;
        }
        plan.selection().fields().forEach(this::field);
        plan.needed().forEach(this::field);
        plan.childLoads().forEach(child -> {
            child.field().through().ifPresent(path -> table(path.key()));
            definition(child.query());
        });
        plan.joinPlans().forEach(join -> fetch(join.plan()));
    }

    void source(SourceQuery<?> source) {
        if (source != null && seen.add(source)) {
            source.checkTreats(this);
        }
    }

    void assignment(Assignment<?, ?> assignment) {
        field(assignment.column());
        if (assignment instanceof Assignment.Computed<?, ?> computed) {
            field(computed.value());
        }
    }
}
