package patch;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class OrderPatchChanges implements Changes<OrderPatch> {
    private final BitSet set = new BitSet();

    private OrderStatus status;

    private BigDecimal total;

    private String note;

    private Long customerId;

    private String city;

    public OrderPatchChanges() {
    }

    public OrderPatchChanges status(OrderStatus value) {
        this.status = value;
        this.set.set(0);
        return this;
    }

    public void setStatus(OrderStatus value) {
        this.status(value);
    }

    public OrderStatus getStatus() {
        return this.status;
    }

    public OrderPatchChanges total(BigDecimal value) {
        this.total = value;
        this.set.set(1);
        return this;
    }

    public void setTotal(BigDecimal value) {
        this.total(value);
    }

    public BigDecimal getTotal() {
        return this.total;
    }

    public OrderPatchChanges note(String value) {
        this.note = value;
        this.set.set(2);
        return this;
    }

    public void setNote(String value) {
        this.note(value);
    }

    public String getNote() {
        return this.note;
    }

    public OrderPatchChanges customerId(Long value) {
        this.customerId = value;
        this.set.set(3);
        return this;
    }

    public void setCustomerId(Long value) {
        this.customerId(value);
    }

    public Long getCustomerId() {
        return this.customerId;
    }

    public OrderPatchChanges city(String value) {
        this.city = value;
        this.set.set(4);
        return this;
    }

    public void setCity(String value) {
        this.city(value);
    }

    public String getCity() {
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
            C value) {
        return value == null ? Assignment.ofNull(column) : Assignment.of(column, value);
    }
}
