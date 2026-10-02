package patch;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QStockPatch {
    public static final TableField<StockEntity, StockEntity> ROOT = TableField.root(StockEntity.class);

    public static final OrderedColumnField<StockPatch, StockEntity, Long> WAREHOUSE_ID = ColumnField.of(StockPatch.class,
            ROOT, "warehouseId", Long.class).named("warehouseId");

    public static final OrderedColumnField<StockPatch, StockEntity, Long> PRODUCT_ID = ColumnField.of(StockPatch.class,
            ROOT, "productId", Long.class).named("productId");

    public static final OrderedColumnField<StockPatch, StockEntity, Integer> QUANTITY = ColumnField.of(StockPatch.class,
            ROOT, "quantity", Integer.class).named("quantity");

    public static final OrderedColumnField<StockPatch, StockEntity, Boolean> BLOCKED = ColumnField.of(StockPatch.class,
            ROOT, "blocked", Boolean.class).named("blocked");

    private QStockPatch() {
    }

    @Incubating
    public static StockPatchChanges changes() {
        return new StockPatchChanges();
    }

    @Incubating
    public static ModelUpdate.Builder<StockEntity, List<Object>, StockPatch> update(
            Changes<StockPatch> changes) {
        return ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.composite(WAREHOUSE_ID,
                PRODUCT_ID)).set(changes);
    }

    @Incubating
    public static ModelDelete.Builder<StockEntity, List<Object>, StockPatch> delete() {
        return ModelDelete.builder(ROOT).primaryKey(PrimaryKey.composite(WAREHOUSE_ID, PRODUCT_ID));
    }
}
