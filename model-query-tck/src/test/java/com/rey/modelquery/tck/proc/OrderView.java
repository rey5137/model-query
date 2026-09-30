package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderStatus;
import java.util.Optional;

/**
 * A class model with a converted column, nested in the record {@link ItemView} and nesting the record
 * {@link CustomerView} through a nullable association: two thirds of the orders have no referrer.
 */
@QueryModel(root = OrderEntity.class)
public class OrderView {

    @PrimaryKey
    private Long id;

    @Column(converter = StatusConverter.class)
    private OrderStatus status;

    // The referrer's key as a plain column, under a name the joined REFERRER_ID does not take.
    @Column(attribute = "referrerId")
    private Long referrerKey;

    // No initialiser: the mapper alone keeps it from being null (R-GEN-15).
    @Join
    private Optional<CustomerView> referrer;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Long getReferrerKey() {
        return referrerKey;
    }

    public void setReferrerKey(Long referrerKey) {
        this.referrerKey = referrerKey;
    }

    public Optional<CustomerView> getReferrer() {
        return referrer;
    }

    public void setReferrer(Optional<CustomerView> referrer) {
        this.referrer = referrer;
    }
}
