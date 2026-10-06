package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code deliveries}: four parties, each a {@code (party_type, party_id)} pair, with the
 * approver pair NULL on the orders that have none (TCK AC-FCH-14, D-114 item 8).
 */
@Entity
@Table(name = "deliveries")
public class DeliveryEntity {
    @Id
    Long id;

    @Column(name = "sender_party_type")
    Integer senderPartyType;

    @Column(name = "sender_party_id")
    Long senderPartyId;

    @Column(name = "recipient_party_type")
    Integer recipientPartyType;

    @Column(name = "recipient_party_id")
    Long recipientPartyId;

    @Column(name = "courier_party_type")
    Integer courierPartyType;

    @Column(name = "courier_party_id")
    Long courierPartyId;

    @Column(name = "approver_party_type")
    Integer approverPartyType;

    @Column(name = "approver_party_id")
    Long approverPartyId;
}
