package com.rey.modelquery.tck.fch;

/**
 * A delivery party's key: a party id and a party type, read from two columns of the model (D-114 item 8). The type
 * is what a lookup splitting its keys by source partitions on (R-FCH-16).
 */
record PartyKey(int partyType, long partyId) {}
