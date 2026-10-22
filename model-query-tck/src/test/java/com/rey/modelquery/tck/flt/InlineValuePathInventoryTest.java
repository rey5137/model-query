package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The 23 value paths the RFC 0018 execution-family audit observed, each named with the TCK test that covers it under
 * {@code hibernate.criteria.value_handling_mode=inline}: a path with no covering test, or a covering test renamed or
 * removed, fails here rather than silently dropping out of the profile (spec api/12 R-FLT-08; TCK AC-FLT-35).
 */
class InlineValuePathInventoryTest {

    /** The audit's paths in its order: 22 confirmed input leaks, then the typed insert-values binding to retain. */
    private static final List<String> AUDITED_PATHS = List.of("comparison-eq", "comparison-ne", "comparison-gt",
            "comparison-ge", "comparison-lt", "comparison-le", "comparison-range", "filter-membership",
            "eq-ignore-case", "like", "ignore-case", "subquery-filter", "byKeys-chunk", "fetch-key", "keyset-UUID",
            "converted-enum", "converted-Boolean", "update-assignment", "delete-filter", "insert-select-filter",
            "conflict-condition", "source-keyset", "typed-insert-binds");

    private static final String FILTERS = "flt.InlineValueFiltersTest#"
            + "ac_flt_35_each_filter_path_binds_its_value_and_returns_the_default_settings_rows";

    /** Each path's covering tests, as {@code package.Class#method} under {@code com.rey.modelquery.tck}. */
    private static final Map<String, List<String>> COVERED_BY = coveredBy();

    private static Map<String, List<String>> coveredBy() {
        Map<String, List<String>> paths = new LinkedHashMap<>();
        Stream.of("comparison-eq", "comparison-ne", "comparison-gt", "comparison-ge", "comparison-lt", "comparison-le",
                        "comparison-range", "eq-ignore-case", "like", "ignore-case", "subquery-filter",
                        "converted-enum", "converted-Boolean")
                .forEach(path -> paths.put(path, List.of(FILTERS)));
        paths.put("filter-membership", List.of(FILTERS,
                "flt.InlineValueFiltersTest#ac_flt_35_later_and_chunked_in_elements_bind"));
        paths.put("byKeys-chunk", List.of("flt.InlineValueKeysTest#"
                + "ac_flt_35_each_key_path_binds_its_keys_and_returns_the_default_settings_rows"));
        paths.put("fetch-key", List.of("flt.InlineValueKeysTest#"
                + "ac_flt_35_fetch_plan_round_keys_bind_and_load_the_default_settings_children"));
        paths.put("keyset-UUID", List.of("flt.InlineValueCursorsTest#"
                + "ac_flt_35_ac_pag_28_each_cursor_binds_its_values_and_reads_the_default_settings_pages"));
        paths.put("update-assignment", List.of("flt.InlineValueUpdatesTest#"
                + "ac_flt_35_update_assignments_and_the_version_check_bind_and_write_the_default_settings_rows"));
        paths.put("delete-filter", List.of("flt.InlineValueWritesTest#"
                + "ac_flt_35_delete_filters_and_keys_bind_and_delete_the_default_settings_rows"));
        paths.put("insert-select-filter", List.of("flt.InlineValueWritesTest#"
                + "ac_flt_35_insert_select_filters_bind_and_write_the_default_settings_rows"));
        paths.put("conflict-condition", List.of(
                "wrt.InsertConflictTest#"
                        + "ac_wrt_25_ac_flt_35_under_inline_value_handling_a_conflict_where_value_binds",
                "wrt.InsertConflictTest#"
                        + "ac_wrt_24_ac_flt_35_under_inline_value_handling_each_statement_binds_the_conflict_"
                        + "where_value",
                "wrt.InsertConflictTest#"
                        + "ac_wrt_45_ac_flt_35_under_inline_value_handling_an_arbiter_predicate_stays_structural"));
        paths.put("source-keyset", List.of(
                "src.SetOperationExecutionTest#"
                        + "ac_src_08_ac_flt_35_under_inline_value_handling_a_keyset_cursor_over_a_feed_"
                        + "binds_its_values",
                "src.SourceQueryExecutionTest#"
                        + "ac_src_01_ac_flt_35_under_inline_value_handling_a_source_where_value_binds",
                "src.ChainedSourceExecutionTest#"
                        + "ac_src_26_ac_flt_35_under_inline_value_handling_each_cte_bodys_filter_values_bind"));
        paths.put("typed-insert-binds", List.of("flt.InlineValueWritesTest#"
                + "ac_flt_35_typed_insert_values_keep_binding_through_their_mapping"));
        return paths;
    }

    @Test
    void ac_flt_35_every_audited_path_names_a_covering_test() {
        assertThat(AUDITED_PATHS).hasSize(23).doesNotHaveDuplicates();
        assertThat(COVERED_BY.keySet()).containsExactlyInAnyOrderElementsOf(AUDITED_PATHS);
        COVERED_BY.forEach((path, tests) -> assertThat(tests).as(path).isNotEmpty()
                .allSatisfy(test -> assertThat(tckTest(test)).as(path + ": " + test).isTrue()));
    }

    @Test
    void ac_flt_35_the_filter_suite_runs_a_case_for_each_filter_path() {
        List<String> cases = Stream.concat(InlineValueFiltersTest.ORDER_CASES.stream().map(c -> c.path()),
                InlineValueFiltersTest.ROW_CASES.stream().map(c -> c.path())).toList();
        COVERED_BY.forEach((path, tests) -> {
            if (tests.contains(FILTERS)) {
                assertThat(cases).as(path).anyMatch(c -> c.equals(path) || c.startsWith(path + " "));
            }
        });
    }

    /** Whether {@code test}, a {@code package.Class#method}, names a {@link TckTest} method over a database. */
    private static boolean tckTest(String test) {
        int hash = test.indexOf('#');
        try {
            Method method = Class.forName("com.rey.modelquery.tck." + test.substring(0, hash))
                    .getDeclaredMethod(test.substring(hash + 1), TckDatabase.class);
            return method.isAnnotationPresent(TckTest.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }
}
