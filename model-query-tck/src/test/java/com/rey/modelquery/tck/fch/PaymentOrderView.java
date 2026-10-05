package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.tck.col.PaymentOrderEntity;

/**
 * A payment order with four actors, each a {@link ActorKey} the enricher fills a profile field from: the payer, the
 * payee, the initiator and the requestor, which is absent on the orders that have none (D-114 item 8). The actor key
 * columns are declared by the enricher's {@code reading(...)}, not by the query's own selection (TCK AC-FCH-14).
 */
@QueryModel(root = PaymentOrderEntity.class)
public record PaymentOrderView(@PrimaryKey Long id,
        Integer payerUserType, Long payerUserId,
        Integer payeeUserType, Long payeeUserId,
        Integer initiatorUserType, Long initiatorUserId,
        Integer requestorUserType, Long requestorUserId,
        @Transient String payer, @Transient String payee,
        @Transient String initiator, @Transient String requestor) {

    ActorKey payerKey() {
        return key(payerUserType, payerUserId);
    }

    ActorKey payeeKey() {
        return key(payeeUserType, payeeUserId);
    }

    ActorKey initiatorKey() {
        return key(initiatorUserType, initiatorUserId);
    }

    /** {@code null} when the order has no requestor, so the enricher never looks it up (R-FCH-15). */
    ActorKey requestorKey() {
        return key(requestorUserType, requestorUserId);
    }

    private static ActorKey key(Integer userType, Long userId) {
        return userType == null || userId == null ? null : new ActorKey(userType, userId);
    }

    PaymentOrderView withPayer(String value) {
        return new PaymentOrderView(id, payerUserType, payerUserId, payeeUserType, payeeUserId, initiatorUserType,
                initiatorUserId, requestorUserType, requestorUserId, value, payee, initiator, requestor);
    }

    PaymentOrderView withPayee(String value) {
        return new PaymentOrderView(id, payerUserType, payerUserId, payeeUserType, payeeUserId, initiatorUserType,
                initiatorUserId, requestorUserType, requestorUserId, payer, value, initiator, requestor);
    }

    PaymentOrderView withInitiator(String value) {
        return new PaymentOrderView(id, payerUserType, payerUserId, payeeUserType, payeeUserId, initiatorUserType,
                initiatorUserId, requestorUserType, requestorUserId, payer, payee, value, requestor);
    }

    PaymentOrderView withRequestor(String value) {
        return new PaymentOrderView(id, payerUserType, payerUserId, payeeUserType, payeeUserId, initiatorUserType,
                initiatorUserId, requestorUserType, requestorUserId, payer, payee, initiator, value);
    }
}
