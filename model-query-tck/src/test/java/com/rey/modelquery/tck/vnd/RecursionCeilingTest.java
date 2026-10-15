package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.src.FolderNode;
import com.rey.modelquery.tck.src.FolderRow;
import com.rey.modelquery.tck.src.QFolderNode;
import com.rey.modelquery.tck.src.QFolderRow;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** The recursive-source depth ceiling, which differs by vendor (spec api/17 AC-SRC-14, R-VND-22). */
class RecursionCeilingTest {

    @TckTest
    void ac_src_14_mysql_refuses_a_max_depth_of_999_with_mq4015(TckDatabase db) {
        var deep = QFolderRow.query(tree(TckFixture.FOLDER_DEEP_ROOT, 999))
                .select(QFolderRow.ALL).orderBy(QFolderRow.ID.asc()).build();
        if (db.vendor() != TckVendor.MYSQL) {
            // H2 and PostgreSQL allow Integer.MAX_VALUE of them (R-VND-22), so 999 is a level the chain simply has
            // not got: it walks all 999 rows and no row is past the limit.
            List<FolderRow> listed = new ArrayList<>();
            SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class,
                    executor -> listed.addAll(executor.list(deep, Limit.unlimited()))));

            assertThat(listed).hasSize(TckFixture.FOLDER_DEEP_NODES);
            return;
        }
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor ->
                assertThatThrownBy(() -> executor.list(deep, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015))
                        .hasMessageContaining("maxDepth(999)")
                        .hasMessageContaining("1000")));

        assertThat(sql).isEmpty();
    }

    private static SourceQuery<FolderNode> tree(long root, int maxDepth) {
        return QFolderNode.source().where(f -> f.eq(QFolderNode.ID, root))
                .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
