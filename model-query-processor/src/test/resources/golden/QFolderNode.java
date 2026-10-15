package tree;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
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

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<FolderNode> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<FolderNode> INSTANCE = FieldIndex.builder(FolderNode.class)
                .select(ID, PARENT_ID, NAME, LEVEL)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
