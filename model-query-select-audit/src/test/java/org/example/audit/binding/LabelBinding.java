package org.example.audit.binding;

import com.rey.modelquery.annotations.BindQueryModel;
import com.rey.modelquery.audit.test.AuditCustomerEntity;
import org.example.audit.bound.CustomerLabel;

@BindQueryModel(model = CustomerLabel.class, root = AuditCustomerEntity.class, prefix = "Bound")
public final class LabelBinding {
    private LabelBinding() {}
}
