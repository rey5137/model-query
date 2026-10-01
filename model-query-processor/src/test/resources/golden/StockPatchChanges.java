package patch;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class StockPatchChanges implements Changes<StockPatch> {
    private final BitSet set = new BitSet();

    private Integer quantity;

    private Boolean blocked;

    public StockPatchChanges() {
    }

    public StockPatchChanges quantity(Integer value) {
        this.quantity = value;
        this.set.set(0);
        return this;
    }

    public void setQuantity(Integer value) {
        this.quantity(value);
    }

    public Integer getQuantity() {
        return this.quantity;
    }

    public StockPatchChanges blocked(Boolean value) {
        this.blocked = value;
        this.set.set(1);
        return this;
    }

    public void setBlocked(Boolean value) {
        this.blocked(value);
    }

    public Boolean getBlocked() {
        return this.blocked;
    }

    @Override
    public boolean isSet(ColumnField<StockPatch, ?, ?> column) {
        if (QStockPatch.QUANTITY.equals(column)) {
            return this.set.get(0);
        }
        if (QStockPatch.BLOCKED.equals(column)) {
            return this.set.get(1);
        }
        return false;
    }

    @Override
    public StockPatchChanges unset(ColumnField<StockPatch, ?, ?> column) {
        if (QStockPatch.QUANTITY.equals(column)) {
            this.quantity = null;
            this.set.clear(0);
            return this;
        }
        if (QStockPatch.BLOCKED.equals(column)) {
            this.blocked = null;
            this.set.clear(1);
            return this;
        }
        return this;
    }

    @Override
    public boolean isEmpty() {
        return this.set.isEmpty();
    }

    @Override
    public List<Assignment<StockPatch, ?>> assignments() {
        List<Assignment<StockPatch, ?>> assignments = new ArrayList<>();
        if (this.set.get(0)) {
            assignments.add(assignment(QStockPatch.QUANTITY, this.quantity));
        }
        if (this.set.get(1)) {
            assignments.add(assignment(QStockPatch.BLOCKED, this.blocked));
        }
        return Collections.unmodifiableList(assignments);
    }

    private static <C> Assignment<StockPatch, C> assignment(ColumnField<StockPatch, ?, C> column,
            C value) {
        return value == null ? Assignment.ofNull(column) : Assignment.of(column, value);
    }
}
