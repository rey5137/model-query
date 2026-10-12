package com.rey.modelquery.tck.vnd;

import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.RowLock.Strength;
import com.rey.modelquery.core.RowLock.Wait;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckVendor;
import java.util.List;
import org.hibernate.Version;

/**
 * The vendor and Hibernate-line facts a locked read's TCK cases need (spec vendor/41 §6): which locks a Tier-1 dialect
 * renders, and whether a second connection waits on a locked row. They live here rather than in {@code tck.lock}
 * because AC-QA-02 confines knowing the vendor to the harness and {@code tck.vnd} (R-QA-03), as
 * {@code tck.vnd.TimeoutAssertions} does for timeouts.
 */
public final class VendorLocks {

    /** Every strength x wait a lock can name, in the order the provider decides them (vendor/41 §6). */
    private static final List<RowLock> ALL = List.of(
            RowLock.exclusive(),
            RowLock.exclusive(Wait.NO_WAIT),
            RowLock.exclusive(Wait.SKIP_LOCKED),
            RowLock.shared(),
            RowLock.shared(Wait.NO_WAIT),
            RowLock.shared(Wait.SKIP_LOCKED));

    private VendorLocks() {}

    /** Whether a second connection's ordinary read waits rather than skipping or failing: PostgreSQL and MySQL. */
    public static boolean blocksOnLockedRow(TckDatabase db) {
        return db.vendor() == TckVendor.POSTGRESQL || db.vendor() == TckVendor.MYSQL;
    }

    /** The locks Hibernate renders here: every combination, bar what H2's dialect drops on the running line. */
    public static List<RowLock> rendered(TckDatabase db) {
        if (db.vendor() != TckVendor.H2) {
            return ALL;
        }
        return ALL.stream().filter(lock -> lock.waitPolicy() == Wait.WAIT
                || (hibernateMajor() >= 7 && lock.strength() == Strength.EXCLUSIVE)).toList();
    }

    /** The rest: a shared lock's wait is dropped on H2 on both lines, an exclusive one's only by 6.6 (vendor/41 §6). */
    public static List<RowLock> refused(TckDatabase db) {
        return ALL.stream().filter(lock -> !rendered(db).contains(lock)).toList();
    }

    /** The running Hibernate's major version, which decides which rows of vendor/41 §6 apply. */
    private static int hibernateMajor() {
        String version = Version.getVersionString();
        return Integer.parseInt(version.substring(0, version.indexOf('.')));
    }
}
