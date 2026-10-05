package com.rey.modelquery.tck.fch;

/**
 * A payment order actor's key: a user id and a user type, read from two columns of the model (D-114 item 8). The type
 * is what a lookup splitting its keys by source partitions on (R-FCH-16).
 */
record ActorKey(int userType, long userId) {}
