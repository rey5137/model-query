package idf;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ValidChanges;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.processing.Generated;

/**
 * The columns of {@link TicketRow} it writes; left out:
 * <ul>
 * <li>{@code buyer}: a to-one; only an {@code @UpdateModel} writes a foreign key</li>
 * </ul>
 */
@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
@ValidChanges(TicketRow.class)
public final class TicketRowChanges implements Changes<TicketRow> {
    private final BitSet set = new BitSet();

    private String title;

    private Long referrerId;

    private String buyerRef;

    public TicketRowChanges() {
    }

    public TicketRowChanges title(String value) {
        this.title = value;
        this.set.set(0);
        return this;
    }

    public void setTitle(String value) {
        this.title(value);
    }

    public String getTitle() {
        return this.title;
    }

    public TicketRowChanges referrerId(Long value) {
        this.referrerId = value;
        this.set.set(1);
        return this;
    }

    public void setReferrerId(Long value) {
        this.referrerId(value);
    }

    public Long getReferrerId() {
        return this.referrerId;
    }

    public TicketRowChanges buyerRef(String value) {
        this.buyerRef = value;
        this.set.set(2);
        return this;
    }

    public void setBuyerRef(String value) {
        this.buyerRef(value);
    }

    public String getBuyerRef() {
        return this.buyerRef;
    }

    @Override
    public boolean isSet(ColumnField<TicketRow, ?, ?> column) {
        if (QTicketRow.TITLE.equals(column)) {
            return this.set.get(0);
        }
        if (QTicketRow.REFERRER_ID.equals(column)) {
            return this.set.get(1);
        }
        if (QTicketRow.BUYER_REF.equals(column)) {
            return this.set.get(2);
        }
        return false;
    }

    @Override
    public TicketRowChanges unset(ColumnField<TicketRow, ?, ?> column) {
        if (QTicketRow.TITLE.equals(column)) {
            this.title = null;
            this.set.clear(0);
            return this;
        }
        if (QTicketRow.REFERRER_ID.equals(column)) {
            this.referrerId = null;
            this.set.clear(1);
            return this;
        }
        if (QTicketRow.BUYER_REF.equals(column)) {
            this.buyerRef = null;
            this.set.clear(2);
            return this;
        }
        return this;
    }

    @Override
    public boolean isEmpty() {
        return this.set.isEmpty();
    }

    @Override
    public List<Assignment<TicketRow, ?>> assignments() {
        List<Assignment<TicketRow, ?>> assignments = new ArrayList<>();
        if (this.set.get(0)) {
            assignments.add(assignment(QTicketRow.TITLE, this.title));
        }
        if (this.set.get(1)) {
            assignments.add(assignment(QTicketRow.REFERRER_ID, this.referrerId));
        }
        if (this.set.get(2)) {
            assignments.add(assignment(QTicketRow.BUYER_REF, this.buyerRef));
        }
        return Collections.unmodifiableList(assignments);
    }

    private static <C> Assignment<TicketRow, C> assignment(ColumnField<TicketRow, ?, C> column,
            C value) {
        return value == null ? Assignment.ofNull(column) : Assignment.of(column, value);
    }

    public static TicketRowChanges from(TicketRow model, SelectSet<TicketRow> columns) {
        Objects.requireNonNull(model, "model");
        TicketRowChanges changes = new TicketRowChanges();
        for (SelectField<TicketRow, ?> column : Objects.requireNonNull(columns, "columns").fields()) {
            if (QTicketRow.TITLE.equals(column)) {
                changes.title(model.title());
            } else if (QTicketRow.REFERRER_ID.equals(column)) {
                changes.referrerId(model.referrerId());
            } else if (QTicketRow.BUYER_REF.equals(column)) {
                changes.buyerRef(model.buyerRef());
            } else {
                throw new ModelQueryDefinitionException(MqCode.MQ1607, column + ": not a column TicketRowChanges writes; a change set writes TicketRow's root, non-key columns only");
            }
        }
        return changes;
    }
}
