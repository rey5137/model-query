package com.rey.modelquery.tck.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.jpa.vendor.HandOffObserver;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The select-audit agent's engine seams (R-INS-19, D-241): private members it instruments by name, which no compiler
 * or layering rule sees. Each must exist with the exact signature the agent matches, so a rename in core or jpa fails
 * here and not silently in the audit.
 */
class SelectAuditSeamsTest {

    @Test
    void ac_ins_19_model_query_to_model_row_seam_exists() throws Exception {
        assertThat(ModelQuery.class.getDeclaredMethod("toModel", Row.class).getReturnType()).isNotNull();
    }

    @Test
    void ac_ins_19_executor_run_plan_owner_rows_seam_exists() throws Exception {
        Class<?> executor = Class.forName("com.rey.modelquery.jpa.DefaultModelQueryExecutor");
        Method run = executor.getDeclaredMethod("run", FetchPlan.class, String.class, List.class);
        assertThat(run.getReturnType()).isEqualTo(List.class);
    }

    @Test
    void ac_ins_19_value_row_get_raw_alias_seams_exist() throws Exception {
        Class<?> valueRow = Class.forName("com.rey.modelquery.core.RowSelection$ValueRow");
        assertThat(Row.class).isAssignableFrom(valueRow);
        assertThat(valueRow.getDeclaredMethod("get", SelectField.class)).isNotNull();
        assertThat(valueRow.getDeclaredMethod("raw", SelectField.class).getReturnType()).isEqualTo(Object.class);
        assertThat(valueRow.getDeclaredMethod("alias", SelectField.class)).isNotNull();
    }

    /**
     * The TCK has the select-audit jar on its test class path, which registers its observer through
     * {@code ServiceLoader}; without the agent that observer is inactive, so the TCK runs on the default
     * path (D-242), keeping no rows. A class path change that moves it onto the observing path fails here.
     */
    @Test
    void ac_ins_24_tck_runs_with_no_hand_off_observer_despite_the_audit_jar() {
        assertThat(HandOffObserver.installed()).isSameAs(HandOffObserver.NONE);
    }

    /**
     * The module's public surface is the allow-list (D-243): the agent, the two ServiceLoader entries, the read
     * hooks called from injected code, the failure and the report. Anything else public ships in the release.
     */
    @Test
    void ac_ins_20_select_audit_public_classes_are_exactly_the_allow_list() {
        // The audit jar is excluded from the TCK runtime, so import the reactor output (as LayeringTest does).
        java.nio.file.Path output = java.nio.file.Path.of("../model-query-select-audit/target/classes");
        assertThat(output).as("audit reactor classes directory").isDirectory();
        var classes = new ClassFileImporter().importPath(output);
        assertThat(classes.stream().filter(c -> c.getModifiers().contains(JavaModifier.PUBLIC))
                .map(JavaClass::getName))
                .containsExactlyInAnyOrder(
                        "com.rey.modelquery.audit.AuditAgent",
                        "com.rey.modelquery.audit.AuditObserver",
                        "com.rey.modelquery.audit.AuditLauncherSessionListener",
                        "com.rey.modelquery.audit.ReadAccess",
                        "com.rey.modelquery.audit.UnselectedColumnRead",
                        "com.rey.modelquery.audit.SelectAuditReport");
    }
}
