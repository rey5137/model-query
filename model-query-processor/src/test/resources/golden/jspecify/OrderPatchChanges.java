package patch;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.jpa.ValidChanges;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
@NullMarked
@ValidChanges(OrderPatch.class)
public final class OrderPatchChanges implements Changes<OrderPatch> {
    private final BitSet set = new BitSet();

    private @Nullable OrderStatus status;

    private @Nullable BigDecimal total;

    private @Nullable String note;

    private @Nullable Long customerId;

    private @Nullable String city;

    public OrderPatchChanges() {
    }

    public OrderPatchChanges status(@Nullable OrderStatus value) {
        this.status = value;
        this.set.set(0);
        return this;
    }

    public void setStatus(@Nullable OrderStatus value) {
        this.status(value);
    }

    public @Nullable OrderStatus getStatus() {
        return this.status;
    }

    public OrderPatchChanges total(@Nullable BigDecimal value) {
        this.total = value;
        this.set.set(1);
        return this;
    }

    public void setTotal(@Nullable BigDecimal value) {
        this.total(value);
    }

    public @Nullable BigDecimal getTotal() {
        return this.total;
    }

    public OrderPatchChanges note(@Nullable String value) {
        this.note = value;
        this.set.set(2);
        return this;
    }

    public void setNote(@Nullable String value) {
        this.note(value);
    }

    public @Nullable String getNote() {
        return this.note;
    }

    public OrderPatchChanges customerId(@Nullable Long value) {
        this.customerId = value;
        this.set.set(3);
        return this;
    }

    public void setCustomerId(@Nullable Long value) {
        this.customerId(value);
    }

    public @Nullable Long getCustomerId() {
        return this.customerId;
    }

    public OrderPatchChanges city(@Nullable String value) {
        this.city = value;
        this.set.set(4);
        return this;
    }

    public void setCity(@Nullable String value) {
        this.city(value);
    }

    public @Nullable String getCity() {
        return this.city;
    }

    @Override
    public boolean isSet(ColumnField<OrderPatch, ?, ?> column) {
        if (QOrderPatch.STATUS.equals(column)) {
            return this.set.get(0);
        }
        if (QOrderPatch.TOTAL.equals(column)) {
            return this.set.get(1);
        }
        if (QOrderPatch.NOTE.equals(column)) {
            return this.set.get(2);
        }
        if (QOrderPatch.CUSTOMER_ID.equals(column)) {
            return this.set.get(3);
        }
        if (QOrderPatch.CITY.equals(column)) {
            return this.set.get(4);
        }
        return false;
    }

    @Override
    public OrderPatchChanges unset(ColumnField<OrderPatch, ?, ?> column) {
        if (QOrderPatch.STATUS.equals(column)) {
            this.status = null;
            this.set.clear(0);
            return this;
        }
        if (QOrderPatch.TOTAL.equals(column)) {
            this.total = null;
            this.set.clear(1);
            return this;
        }
        if (QOrderPatch.NOTE.equals(column)) {
            this.note = null;
            this.set.clear(2);
            return this;
        }
        if (QOrderPatch.CUSTOMER_ID.equals(column)) {
            this.customerId = null;
            this.set.clear(3);
            return this;
        }
        if (QOrderPatch.CITY.equals(column)) {
            this.city = null;
            this.set.clear(4);
            return this;
        }
        return this;
    }

    @Override
    public boolean isEmpty() {
        return this.set.isEmpty();
    }

    @Override
    public List<Assignment<OrderPatch, ?>> assignments() {
        List<Assignment<OrderPatch, ?>> assignments = new ArrayList<>();
        if (this.set.get(0)) {
            assignments.add(assignment(QOrderPatch.STATUS, this.status));
        }
        if (this.set.get(1)) {
            assignments.add(assignment(QOrderPatch.TOTAL, this.total));
        }
        if (this.set.get(2)) {
            assignments.add(assignment(QOrderPatch.NOTE, this.note));
        }
        if (this.set.get(3)) {
            assignments.add(assignment(QOrderPatch.CUSTOMER_ID, this.customerId));
        }
        if (this.set.get(4)) {
            assignments.add(assignment(QOrderPatch.CITY, this.city));
        }
        return Collections.unmodifiableList(assignments);
    }

    private static <C> Assignment<OrderPatch, C> assignment(ColumnField<OrderPatch, ?, C> column,
            @Nullable C value) {
        return value == null ? Assignment.ofNull(column) : Assignment.of(column, value);
    }
}
