package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code payment_orders}: four actors, each a {@code (user_type, user_id)} pair, with the
 * requestor pair NULL on the orders that have none (TCK AC-FCH-14, D-114 item 8).
 */
@Entity
@Table(name = "payment_orders")
public class PaymentOrderEntity {
    @Id
    Long id;

    @Column(name = "payer_user_type")
    Integer payerUserType;

    @Column(name = "payer_user_id")
    Long payerUserId;

    @Column(name = "payee_user_type")
    Integer payeeUserType;

    @Column(name = "payee_user_id")
    Long payeeUserId;

    @Column(name = "initiator_user_type")
    Integer initiatorUserType;

    @Column(name = "initiator_user_id")
    Long initiatorUserId;

    @Column(name = "requestor_user_type")
    Integer requestorUserType;

    @Column(name = "requestor_user_id")
    Long requestorUserId;
}
