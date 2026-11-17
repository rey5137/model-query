package org.example.audit;

import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;

public final class FirstProjection {
    public static final ColumnField<ManualView, AuditOrderEntity, Long> VALUE = QAuditOrderView.TOTAL
            .withTable(ManualView.class, QAuditOrderView.ROOT).named("total");
    public static final RowMapper<ManualView> MAPPER = row -> new ManualView(row.get(VALUE));

    private FirstProjection() {}

    public static FieldIndex<ManualView> fields() {
        return FieldIndex.builder(ManualView.class).select(VALUE).build();
    }

    public static ModelQuery<AuditOrderEntity, ?, ManualView> query() {
        return ModelQuery.builder(QAuditOrderView.ROOT, MAPPER).select(SelectSet.of(VALUE)).build();
    }
}
