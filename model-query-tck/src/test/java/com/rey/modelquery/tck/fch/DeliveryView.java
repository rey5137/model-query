package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.tck.col.DeliveryEntity;

/**
 * A delivery with four parties, each a {@link PartyKey} the enricher fills a profile field from: the sender, the
 * recipient, the courier and the approver, which is absent on the orders that have none (D-114 item 8). The party key
 * columns are declared by the enricher's {@code reading(...)}, not by the query's own selection (TCK AC-FCH-14).
 */
@QueryModel(root = DeliveryEntity.class)
public record DeliveryView(@PrimaryKey Long id,
        Integer senderPartyType, Long senderPartyId,
        Integer recipientPartyType, Long recipientPartyId,
        Integer courierPartyType, Long courierPartyId,
        Integer approverPartyType, Long approverPartyId,
        @Transient String sender, @Transient String recipient,
        @Transient String courier, @Transient String approver) {

    PartyKey senderKey() {
        return key(senderPartyType, senderPartyId);
    }

    PartyKey recipientKey() {
        return key(recipientPartyType, recipientPartyId);
    }

    PartyKey courierKey() {
        return key(courierPartyType, courierPartyId);
    }

    /** {@code null} when the order has no approver, so the enricher never looks it up (R-FCH-15). */
    PartyKey approverKey() {
        return key(approverPartyType, approverPartyId);
    }

    private static PartyKey key(Integer partyType, Long partyId) {
        return partyType == null || partyId == null ? null : new PartyKey(partyType, partyId);
    }

    DeliveryView withSender(String value) {
        return new DeliveryView(id, senderPartyType, senderPartyId, recipientPartyType, recipientPartyId,
                courierPartyType, courierPartyId, approverPartyType, approverPartyId, value, recipient, courier, approver);
    }

    DeliveryView withRecipient(String value) {
        return new DeliveryView(id, senderPartyType, senderPartyId, recipientPartyType, recipientPartyId,
                courierPartyType, courierPartyId, approverPartyType, approverPartyId, sender, value, courier, approver);
    }

    DeliveryView withCourier(String value) {
        return new DeliveryView(id, senderPartyType, senderPartyId, recipientPartyType, recipientPartyId,
                courierPartyType, courierPartyId, approverPartyType, approverPartyId, sender, recipient, value, approver);
    }

    DeliveryView withApprover(String value) {
        return new DeliveryView(id, senderPartyType, senderPartyId, recipientPartyType, recipientPartyId,
                courierPartyType, courierPartyId, approverPartyType, approverPartyId, sender, recipient, courier, value);
    }
}
