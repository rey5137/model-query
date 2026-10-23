package com.rey.modelquery.spike.rfc0021;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * RFC 0021 §Spike plan: 3 hierarchies of {@value #NODES} nodes each (node k's parent is {@code (k - 2) / 3 + 1}, a
 * fan-out of 3), closure edges for 1 to 6 hops (a node closer than 6 levels to the root has fewer, and the root none,
 * so some nodes have no edge at a requested {@code hops}), a unique index on {@code (hierarchy_id, end_vertex, hops)},
 * 5% deleted profiles, 5% deleted nodes and 2% deleted edges. Node {@code k} of every hierarchy is profile {@code k}.
 * Deterministic (seeded {@link Random}).
 */
public final class Rfc0021Dataset {

    public static final int HIERARCHIES = 3;
    public static final int NODES = 5_000;
    public static final int PROFILES = NODES;
    public static final int TYPES = 10;
    public static final int MAX_HOPS = 6;
    public static final LocalDateTime EPOCH = LocalDateTime.of(2025, 1, 1, 0, 0, 0);

    private Rfc0021Dataset() {}

    public static long nodeId(int hierarchy, int k) {
        return (hierarchy - 1L) * NODES + k;
    }

    public static void seed(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        Random random = new Random(21);
        try (PreparedStatement ps = conn.prepareStatement("insert into hierarchy (id, name) values (?, ?)")) {
            for (int h = 1; h <= HIERARCHIES; h++) {
                ps.setLong(1, h);
                ps.setString(2, "H" + h);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement("insert into profile_type (id, name) values (?, ?)")) {
            for (int t = 1; t <= TYPES; t++) {
                ps.setLong(1, t);
                ps.setString(2, "Type " + t);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into profile (id, first_name, last_name, type_id, is_deleted) values (?, ?, ?, ?, ?)")) {
            for (int id = 1; id <= PROFILES; id++) {
                ps.setLong(1, id);
                ps.setString(2, String.format("P%04d", id));
                ps.setString(3, String.format("L%04d", id));
                ps.setLong(4, 1 + id % TYPES);
                ps.setBoolean(5, random.nextInt(100) < 5);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        nodesAndEdges(conn, random);
        members(conn);
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static void nodesAndEdges(Connection conn, Random random) throws SQLException {
        try (PreparedStatement node = conn.prepareStatement(
                "insert into hierarchy_node (id, hierarchy_id, node_id, is_deleted) values (?, ?, ?, ?)");
                PreparedStatement edge = conn.prepareStatement("insert into hierarchy_edge "
                        + "(id, hierarchy_id, start_vertex, end_vertex, hops, is_deleted, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)")) {
            long edgeId = 0;
            for (int h = 1; h <= HIERARCHIES; h++) {
                for (int k = 1; k <= NODES; k++) {
                    node.setLong(1, nodeId(h, k));
                    node.setLong(2, h);
                    node.setLong(3, k);
                    node.setBoolean(4, random.nextInt(100) < 5);
                    node.addBatch();
                    int ancestor = k;
                    for (int hops = 1; hops <= MAX_HOPS && ancestor > 1; hops++) {
                        ancestor = (ancestor - 2) / 3 + 1;
                        edge.setLong(1, ++edgeId);
                        edge.setLong(2, h);
                        edge.setLong(3, ancestor);
                        edge.setLong(4, k);
                        edge.setInt(5, hops);
                        edge.setBoolean(6, random.nextInt(100) < 2);
                        edge.setTimestamp(7, Timestamp.valueOf(EPOCH.plusMinutes(edgeId)));
                        edge.addBatch();
                    }
                }
                node.executeBatch();
                edge.executeBatch();
            }
        }
    }

    /** Both composite-id tables hold (hierarchy, profile) for every profile whose id is not a multiple of 7. */
    private static void members(Connection conn) throws SQLException {
        for (String table : new String[] {"member_ic", "member_emb"}) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "insert into " + table + " (hierarchy_id, profile_id, role) values (?, ?, ?)")) {
                for (int h = 1; h <= HIERARCHIES; h++) {
                    for (int p = 1; p <= PROFILES; p++) {
                        if (p % 7 != 0) {
                            ps.setLong(1, h);
                            ps.setLong(2, p);
                            ps.setString(3, "R" + p % 4);
                            ps.addBatch();
                        }
                    }
                }
                ps.executeBatch();
            }
        }
    }
}
