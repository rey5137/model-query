package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fork files of an older run are skipped by the merge, files of an unknown run never are (R-INS-21, D-244). */
class AcInsRunMergeTest {

    @TempDir
    Path dir;

    private void fork(String name, String header, String read) throws IOException {
        String text = (header == null ? "" : header + "\n")
                + "# CALL_SITE\tSite.load:1\tlist ALL\tcom.example.Order\tid,name\t" + read + "\t1\n";
        Files.writeString(dir.resolve(name), text);
    }

    private List<String> mergeWarnings(Path directory, java.util.function.Consumer<String> merged) throws Exception {
        Logger logger = Logger.getLogger(SelectAuditReport.class.getName());
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            merged.accept(SelectAuditReport.merge(directory));
        } finally {
            logger.removeHandler(handler);
        }
        return warnings;
    }

    @Test
    void ac_ins_22_merge_keeps_only_the_newest_run_and_warns_once() throws Exception {
        fork("select-audit-fork-1-10.txt", "# RUN\tpid-1-1000\t1000", "id,name");
        fork("select-audit-fork-1-20.txt", "# RUN\tpid-2-5000\t5000", "id");
        fork("select-audit-fork-2-21.txt", "# RUN\tpid-2-5000\t5100", "id");
        List<String> warnings = mergeWarnings(dir, merged -> assertThat(merged).contains("Order.name")
                .doesNotContain("Order.id"));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("skipped 1 fork file");
    }

    @Test
    void ac_ins_22_one_run_with_several_forks_merges_without_warning() throws Exception {
        fork("select-audit-fork-1-10.txt", "# RUN\tpid-2-5000\t5000", "id");
        fork("select-audit-fork-2-11.txt", "# RUN\tpid-2-5000\t5400", "name");
        List<String> warnings = mergeWarnings(dir, merged -> assertThat(merged).doesNotContain("Order."));
        assertThat(warnings).isEmpty();
    }

    @Test
    void ac_ins_22_a_fork_of_unknown_run_always_merges_with_the_newest_run() throws Exception {
        fork("select-audit-fork-1-10.txt", "# RUN\tpid-1-1000\t1000", "id,name");
        fork("select-audit-fork-1-20.txt", "# RUN\tpid-2-5000\t5000", "id");
        fork("select-audit-fork-2-21.txt", "# RUN\tunknown\t0", "name");
        fork("select-audit-22.txt", null, "name");
        List<String> warnings = mergeWarnings(dir, merged -> assertThat(merged).doesNotContain("Order."));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("skipped 1 fork file");
    }

    @Test
    void ac_ins_22_only_unknown_runs_merge_everything() throws Exception {
        fork("select-audit-1.txt", "# RUN\tunknown\t0", "id");
        fork("select-audit-2.txt", null, "name");
        List<String> warnings = mergeWarnings(dir, merged -> assertThat(merged).doesNotContain("Order."));
        assertThat(warnings).isEmpty();
    }

    @Test
    void ac_ins_22_a_written_fork_file_carries_the_run_property() throws Exception {
        String previous = System.getProperty(SelectAuditReport.RUN_PROPERTY);
        try {
            System.setProperty(SelectAuditReport.RUN_PROPERTY, "build-7");
            Path file = SelectAuditReport.writeForkReport(dir);
            assertThat(Files.readAllLines(file).get(0)).startsWith("# RUN\tbuild-7\t");
        } finally {
            if (previous == null) {
                System.clearProperty(SelectAuditReport.RUN_PROPERTY);
            } else {
                System.setProperty(SelectAuditReport.RUN_PROPERTY, previous);
            }
        }
    }
}
