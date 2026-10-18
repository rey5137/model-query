package tree;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QFolderNode {
    public static final TableField<FolderEntity, FolderEntity> ROOT = TableField.root(FolderEntity.class);

    public static final OrderedColumnField<FolderNode, FolderEntity, Long> ID = ColumnField.of(FolderNode.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<FolderNode, FolderEntity, Long> PARENT_ID = ColumnField.of(FolderNode.class,
            ROOT, "parentId", Long.class).named("parentId");

    public static final OrderedColumnField<FolderNode, FolderEntity, String> NAME = ColumnField.of(FolderNode.class,
            ROOT, "name", String.class).named("name");

    public static final OrderedColumnField<FolderNode, FolderEntity, Integer> LEVEL = ColumnField.of(FolderNode.class,
            ROOT, "level", Integer.class).named("level");

    public static final SelectSet<FolderNode> ALL = SelectSet.of(ID, PARENT_ID, NAME, LEVEL);

    public static final SelectSet<FolderNode> DEFAULT = ALL;

    public static final PrimaryKey<FolderNode, Long> KEY = PrimaryKey.of(ID);

    private QFolderNode() {
    }

    public static SourceQuery.Builder<FolderEntity, FolderNode, FolderNode> source() {
        return SourceQuery.builder(FolderNode.class, ROOT, ALL, KEY).depth(LEVEL);
    }

    @Incubating
    public static ModelQuery.Builder<FolderNode, Long, FolderNode> query(
            SourceQuery<FolderNode> source) {
        return ModelQuery.builder(Rows.ROOT, Rows.MAPPER, source).primaryKey(Rows.KEY);
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<FolderNode> fields() {
        return Index.INSTANCE;
    }

    @com.rey.modelquery.annotations.Incubating
    public static final class Rows {
        public static final com.rey.modelquery.core.TableField<tree.FolderNode, tree.FolderNode> ROOT = com.rey.modelquery.core.TableField.source(tree.FolderNode.class);

        public static final com.rey.modelquery.core.OrderedColumnField<tree.FolderNode, tree.FolderNode, java.lang.Long> ID = com.rey.modelquery.core.ColumnField.over(tree.FolderNode.class,
                ROOT, tree.QFolderNode.ID).named("id");

        public static final com.rey.modelquery.core.OrderedColumnField<tree.FolderNode, tree.FolderNode, java.lang.Long> PARENT_ID = com.rey.modelquery.core.ColumnField.over(tree.FolderNode.class,
                ROOT, tree.QFolderNode.PARENT_ID).named("parentId");

        public static final com.rey.modelquery.core.OrderedColumnField<tree.FolderNode, tree.FolderNode, java.lang.String> NAME = com.rey.modelquery.core.ColumnField.over(tree.FolderNode.class,
                ROOT, tree.QFolderNode.NAME).named("name");

        public static final com.rey.modelquery.core.OrderedColumnField<tree.FolderNode, tree.FolderNode, java.lang.Integer> LEVEL = com.rey.modelquery.core.ColumnField.over(tree.FolderNode.class,
                ROOT, tree.QFolderNode.LEVEL).named("level");

        public static final com.rey.modelquery.core.SelectSet<tree.FolderNode> ALL = com.rey.modelquery.core.SelectSet.of(ID,
                PARENT_ID, NAME, LEVEL);

        public static final com.rey.modelquery.core.SelectSet<tree.FolderNode> DEFAULT = ALL;

        public static final com.rey.modelquery.core.PrimaryKey<tree.FolderNode, java.lang.Long> KEY = com.rey.modelquery.core.PrimaryKey.of(ID);

        public static final com.rey.modelquery.core.RowMapper<tree.FolderNode> MAPPER = tree.QFolderNode.Rows::map;

        private Rows() {
        }

        private static tree.FolderNode map(com.rey.modelquery.core.Row row) {
            return new tree.FolderNode(row.get(ID), row.get(PARENT_ID), row.get(NAME),
                    row.get(LEVEL));
        }
    }

    private static final class Index {
        static final FieldIndex<FolderNode> INSTANCE = FieldIndex.builder(FolderNode.class)
                .select(ID, PARENT_ID, NAME, LEVEL)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
