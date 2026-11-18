package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import com.rey.modelquery.annotations.Column;
import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.example.audit.ignored.IgnoredReader;

/** AC-INS-21: serializers warn and ignored readers do not count. */
public class AcIns21Test {
    @Test
    void jackson3WarnsForUnselectedAndCountsSelected() throws Exception {
        Bean bean = new Bean();
        Tag tag = tag(bean);
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(bean);
        assertThat(json).contains("\"id\":1");
        assertThat(tag.wasRead("id")).isTrue();
        assertThat(tag.unselectedReads()).singleElement().satisfies(event -> {
            assertThat(event.property()).isEqualTo("missing");
            assertThat(event.serializer()).isTrue();
        });
    }

    public static final class Bean implements IgnoredReader.Projection {
        @Column private final Long id = 1L;
        @Column private final String missing = null;
        public Long getId() { return id; }
        public String getMissing() { return missing; }
    }

    @Test
    void ac_ins_21_afterburner_generated_accessors_are_serializer_reads() throws Exception {
        Bean bean = new Bean();
        Tag tag = tag(bean);
        ObjectMapper mapper = new ObjectMapper().registerModule(new AfterburnerModule());
        assertThat(mapper.writeValueAsString(bean)).contains("\"id\":1");
        assertThat(tag.wasRead("id")).isTrue();
        assertThat(tag.unselectedReads()).singleElement().satisfies(event -> {
            assertThat(event.serializer()).isTrue();
            assertThat(event.reader()).contains("com.fasterxml.jackson.module.afterburner.");
        });
    }

    @SuppressWarnings("unchecked")
    private static Tag tag(Bean bean) throws Exception {
        BitSet selected = new BitSet();
        selected.set(0);
        Tag tag = new Tag("Caller.load:12", "list", null, selected, Map.of("id", 0, "missing", 1));
        Field field = AuditObserver.class.getDeclaredField("TAGS");
        field.setAccessible(true);
        ((WeakIdentityMap<Object, Tag>) field.get(null)).put(bean, tag);
        return tag;
    }

    @Test
    void ac_ins_21_jackson_plain_and_blackbird_warn_without_failing() throws Exception {
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = java.util.logging.Logger.getLogger(ReadAccess.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            for (boolean blackbird : new boolean[] {false, true}) {
                Bean bean = new Bean();
                Tag tag = tag(bean);
                ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
                if (blackbird) {
                    mapper.registerModule(new BlackbirdModule());
                }
                assertThat(mapper.writeValueAsString(bean)).isEqualTo("{\"id\":1}");
                assertThat(tag.wasRead("id")).isTrue();
                assertThat(tag.unselectedReads()).singleElement().satisfies(event -> {
                    assertThat(event.property()).isEqualTo("missing");
                    assertThat(event.serializer()).isTrue();
                });
            }
            assertThat(records).hasSize(2).allSatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING);
                assertThat(record.getMessage()).contains("Bean.missing was not selected");
            });
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static void ignoredReader(Bean bean) {
        bean.getId();
        bean.getMissing();
    }

    private static void otherLambda(Bean bean) {
        Runnable reader = () -> bean.getMissing();
        reader.run();
    }

    @Test
    void ac_ins_21_method_ignore_covers_its_lambda_reader() throws Exception {
        Bean bean = new Bean();
        Tag tag = tag(bean);
        System.setProperty("modelquery.selectAudit.ignore",
                getClass().getName() + "#ac_ins_21_method_ignore_covers_its_lambda_reader");
        try {
            org.junit.jupiter.api.Assertions.assertAll(() -> {
                bean.getId();
                bean.getMissing();
            });
            assertThat(tag.wasRead("id")).isTrue();
            assertThat(tag.unselectedReads()).isEmpty();
            assertThatThrownBy(() -> otherLambda(bean)).isInstanceOf(UnselectedColumnRead.class)
                    .hasMessageContaining("lambda$otherLambda$");
        } finally {
            System.clearProperty("modelquery.selectAudit.ignore");
        }
    }

    @Test
    void ac_ins_21_ignored_reader_counts_selected_column_and_takes_no_stack_walk_on_selected_reads()
            throws Exception {
        ReadEvents.clear();
        BitSet selected = new BitSet();
        selected.set(0);
        Bean bean = new Bean();
        Tag tag = new Tag("Caller.load:99", "list", null, "ALL", "Bean", selected,
                Map.of("id", 0, "missing", 1), java.util.Set.of("id", "missing"), Map.of(), java.util.List.of("id"));
        putTag(bean, tag);
        System.setProperty("modelquery.selectAudit.ignore", IgnoredReader.class.getName() + "#read");
        try {
            long before = ReadSite.CAPTURES.sum();
            bean.getId();
            assertThat(ReadSite.CAPTURES.sum()).isEqualTo(before);
            IgnoredReader.read(bean);
            assertThat(tag.unselectedReads()).isEmpty();
            assertThat(tag.callSiteStats().readProperties).containsExactly("id");
        } finally {
            System.clearProperty("modelquery.selectAudit.ignore");
            ReadEvents.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static void putTag(Bean bean, Tag tag) throws Exception {
        Field field = AuditObserver.class.getDeclaredField("TAGS");
        field.setAccessible(true);
        ((WeakIdentityMap<Object, Tag>) field.get(null)).put(bean, tag);
    }

    @Test
    void ac_ins_21_library_method_reference_needs_library_package_rule() throws Exception {
        Bean bean = new Bean();
        System.setProperty("modelquery.selectAudit.ignore",
                getClass().getName() + "#ac_ins_21_library_method_reference_needs_library_package_rule");
        try {
            tag(bean);
            assertThatThrownBy(() -> assertThat(bean).extracting(Bean::getMissing))
                    .isInstanceOf(UnselectedColumnRead.class).hasMessageContaining("org.assertj.");
            System.setProperty("modelquery.selectAudit.ignore", "org.assertj.");
            assertThat(bean).extracting(Bean::getMissing).isNull();
        } finally {
            System.clearProperty("modelquery.selectAudit.ignore");
        }
    }

    @Test
    void ac_ins_21_ignored_caller_does_not_ignore_the_actual_reader() throws Exception {
        Bean bean = new Bean();
        Tag tag = tag(bean);
        System.setProperty("modelquery.selectAudit.ignore", IgnoredReader.class.getName() + "#callOther");
        try {
            assertThatThrownBy(() -> IgnoredReader.callOther(bean)).isInstanceOf(UnselectedColumnRead.class);
            assertThat(tag.unselectedReads()).singleElement()
                    .satisfies(event -> assertThat(event.property()).isEqualTo("missing"));
        } finally {
            System.clearProperty("modelquery.selectAudit.ignore");
        }
    }

    @Test
    void ac_ins_21_class_method_and_package_ignores_never_fail_and_selected_reads_count() throws Exception {
        for (String rule : new String[] {IgnoredReader.class.getName() + "#read",
                "org.example.audit.ignored."}) {
            Bean bean = new Bean();
            Tag tag = tag(bean);
            System.setProperty("modelquery.selectAudit.ignore", rule);
            try {
                IgnoredReader.read(bean);
                assertThat(tag.wasRead("id")).isTrue();
                assertThat(tag.unselectedReads()).isEmpty();
                assertThatThrownBy(() -> IgnoredReader.callOther(bean)).isInstanceOf(UnselectedColumnRead.class);
            } finally {
                System.clearProperty("modelquery.selectAudit.ignore");
            }
        }
    }
}
