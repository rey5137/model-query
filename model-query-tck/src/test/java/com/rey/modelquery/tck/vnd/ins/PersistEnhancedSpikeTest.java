package com.rey.modelquery.tck.vnd.ins;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.hibernate.engine.spi.ManagedEntity;

/**
 * The D-116 vendor spike, {@code persist}: a bytecode-enhanced entity whose fields are set by reflection, as the
 * engine sets them through the attribute's metamodel member, is written as set. Plain JPA, no insert API.
 */
class PersistEnhancedSpikeTest {

    @TckTest
    void d_116_persist_writes_fields_set_by_reflection_on_an_enhanced_entity(TckDatabase db) {
        var loader = new EnhancingClassLoader(InsEnhancedEntity.class);
        Class<?> type = loader.enhanced();
        assertThat(ManagedEntity.class.isAssignableFrom(type)).isTrue();
        try (InsertProbes p = InsertProbes.open(db, loader)) {
            Object id = p.inTransaction(s -> {
                Object entity = instantiate(type);
                set(entity, "code", "e1");
                set(entity, "note", "a lazy note"); // a lazy basic attribute: enhancement intercepts its reads
                s.persist(entity);
                s.flush();
                Object key = s.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
                s.detach(entity);
                assertThat(s.contains(entity)).isFalse();
                return key;
            });
            assertThat(p.statements()).filteredOn(sql -> sql.startsWith("insert into ins_enhanced")).hasSize(1);
            // the unnamed status keeps the constructor's value and the unnamed name is NULL, not a column default
            assertThat(p.rows("select code, note, status, name, version from ins_enhanced where id = " + id))
                    .containsExactly("e1|a lazy note|NEW|null|0");
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object entity, String name, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
