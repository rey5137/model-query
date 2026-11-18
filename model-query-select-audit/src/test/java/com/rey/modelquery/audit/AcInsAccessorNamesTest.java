package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ColumnField;
import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AcInsAccessorNamesTest {
    record RecordModel(@Column Boolean isPaid, @Column String getCode) {}

    static final class Bean {
        @Column private final boolean isActive = false;
        public boolean isActive() { return isActive; }
    }

    @SuppressWarnings("unchecked")
    private static void tag(Object model, String property) throws Exception {
        Field field = AuditObserver.class.getDeclaredField("TAGS");
        field.setAccessible(true);
        ((WeakIdentityMap<Object, Tag>) field.get(null)).put(model,
                new Tag("Caller.load:12", "list", null, new BitSet(), Map.of(property, 0)));
    }

    @Test
    void ac_ins_18_record_accessor_names_are_not_bean_stripped() throws Exception {
        RecordModel paid = new RecordModel(false, null);
        tag(paid, "isPaid");
        assertThatThrownBy(paid::isPaid).isInstanceOf(UnselectedColumnRead.class).hasMessageContaining("isPaid");
        RecordModel code = new RecordModel(false, null);
        tag(code, "getCode");
        assertThatThrownBy(code::getCode).isInstanceOf(UnselectedColumnRead.class).hasMessageContaining("getCode");
    }

    @Test
    void ac_ins_18_class_accessor_falls_back_to_raw_mapped_name() throws Exception {
        Bean bean = new Bean();
        tag(bean, "isActive");
        assertThatThrownBy(bean::isActive).isInstanceOf(UnselectedColumnRead.class).hasMessageContaining("isActive");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ac_ins_18_metadata_maps_raw_boolean_field_name() throws Exception {
        FieldIndex fields = mock(FieldIndex.class);
        when(fields.select("isActive")).thenReturn(Optional.of(mock(ColumnField.class)));
        var constructor = ModelProperties.class.getDeclaredConstructor(Class.class, Class.class, Class.class,
                FieldIndex.class, String.class);
        constructor.setAccessible(true);
        var properties = constructor.newInstance(AcInsAccessorNamesTest.class, Bean.class, Bean.class, fields, "");
        assertThat(properties.mapped()).contains("isActive");
        assertThat(properties.propertyBits()).containsKey("isActive");
    }
}
