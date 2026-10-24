package com.rey.modelquery.tck.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityJoinPortabilityTest {

    static final class Keys {}

    @Test
    void ac_src_58_missing_entity_join_support_is_reported_on_each_target() {
        var root = TableField.root(OrderEntity.class);
        var key = ColumnField.of(Keys.class, root, "id", Long.class).named("id");
        var source = SourceQuery.builder(Keys.class, root, SelectSet.of(key), PrimaryKey.of(key)).build();
        var id = ColumnField.of(Long.class, root, "id", Long.class);
        var bound = SourceJoin.of("keys", Keys.class, JoinKind.LEFT, List.of(SourceJoin.on(id, key)));
        var anchor = ColumnField.over(Long.class, bound.root(), key);
        var entity = EntityJoin.of("customer", CustomerEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(anchor, "id")));
        var query = ModelQuery.builder(root, row -> row.get(id)).joinSource(bound, source).joinEntity(entity)
                .select(SelectSet.of(id)).build();
        var targets = List.of(Target.of(DatabaseVendor.H2, "2.3.232"),
                Target.of(DatabaseVendor.POSTGRESQL, "15.4"), Target.of(DatabaseVendor.MYSQL, "8.0.36"));
        List<Class<?>> entities = OfflineBootTest.TCK_UNIT.stream().<Class<?>>map(name -> {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }).toList();
        NoEntityJoinProviderSupport.serving(() -> {
            var check = PortabilityCheck.of(entities, ModelQueryConfig.defaults())
                    .targets(targets.toArray(Target[]::new))
                    .call("ENTITY", OrderEntity.class, x -> x.list(query, Limit.of(10)));
            assertThatThrownBy(check::assertPortable).isInstanceOfSatisfying(AssertionError.class, failure -> {
                List<String> lines = failure.getMessage().lines().toList();
                assertThat(lines).hasSize(4);
                for (var target : targets) {
                    assertThat(lines).anySatisfy(line -> assertThat(line)
                            .startsWith("ENTITY on " + target + ": REFUSED MQ4014").contains("entityJoins"));
                }
            });
        });
    }
}
