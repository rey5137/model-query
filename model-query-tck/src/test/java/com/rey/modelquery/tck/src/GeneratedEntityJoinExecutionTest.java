package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;

/** Processor-generated entity joins execute the same query as hand-written forms (AC-SRC-53, AC-PROC-48). */
class GeneratedEntityJoinExecutionTest {

    @TckTest
    void ac_src_53_ac_proc_48_generated_left_entity_joins_match_native_sql(TckDatabase db) throws SQLException {
        assertThat(QEntityJoinDetail.fields().resolve(List.of("viaOrder")).unknown()).isEmpty();
        assertThat(QEntityJoinDetail.MANAGER_VIP.identityPath()).isEqualTo("manager.vip");
        assertThat(QEntityJoinDetail.CUSTOMER_VIP.identityPath()).isEqualTo("customer.vip");
        var keys = QEntityJoinKeys.source()
                .where(f -> f.gte(QEntityJoinKeys.ID, 990L).lte(QEntityJoinKeys.ID, 1020L)).build();
        var query = QEntityJoinDetail.query(keys).select(QEntityJoinDetail.ALL)
                .where(f -> f.gte(QEntityJoinDetail.ID, 990L).lte(QEntityJoinDetail.ID, 1030L))
                .orderBy(QEntityJoinDetail.ID.asc()).build();
        List<EntityJoinDetail> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(query, Limit.unlimited())));
            }
        });
        assertThat(rows.stream().map(r ->
                SourceJoinExecutionTest.text(r.id(), r.managerName(), r.customerName(), r.viaOrder())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, m.name, c.name, t.name from orders o
                        left join (select id, customer_id from orders where id between 990 and 1020) k
                            on k.id = o.id
                        left join customers m on m.id = k.id
                        left join customers c on c.id = k.customer_id
                        left join orders cp on cp.id = k.id
                        left join customers t on t.id = cp.customer_id
                        where o.id between 990 and 1030 order by o.id"""));
        assertThat(rows).hasSize(41)
                .contains(new EntityJoinDetail(990L, "Customer 0990", "Customer 0631", "Customer 0631"))
                .anySatisfy(r -> {
                    assertThat(r.id()).isEqualTo(1001L);
                    assertThat(r.managerName()).isNull();
                    assertThat(r.customerName()).isNotNull();
                    assertThat(r.viaOrder()).isEqualTo(r.customerName());
                })
                .contains(new EntityJoinDetail(1021L, null, null, null));
    }
}
