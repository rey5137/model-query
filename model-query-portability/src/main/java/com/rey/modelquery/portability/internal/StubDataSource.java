package com.rey.modelquery.portability.internal;

import com.rey.modelquery.portability.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The deny-by-default {@code DataSource} a dry-run factory boots over: it reaches no database. It hands out a
 * connection only inside a {@linkplain JdbcRecording#call() call} recording; every statement-producing entry point
 * ({@code createStatement}, {@code prepareStatement}, {@code prepareCall}) records its SQL and throws, transaction
 * calls do nothing, {@code DatabaseMetaData} answers the target's product name and version alone, and any other
 * call, {@code unwrap} to a vendor type among them, is recorded and throws. It holds no per-call state: the thread's
 * recording does.
 *
 * @implSpec R-INS-10
 */
final class StubDataSource {

    /** The connection calls that change or read transaction and session state; they reach no database. */
    private static final Set<String> NO_OPS = Set.of("setAutoCommit", "commit", "rollback", "close", "clearWarnings",
            "setReadOnly", "setTransactionIsolation", "setHoldability", "setSchema", "setCatalog");

    private static final Set<String> STATEMENTS = Set.of("createStatement", "prepareStatement", "prepareCall");

    private StubDataSource() {
    }

    /** A stub data source answering {@code target}'s product name and version. */
    static DataSource of(Target target) {
        return proxy(DataSource.class, new Handler("DataSource", target) {
            @Override
            Object answer(Object self, String name, Object[] args) throws SQLException {
                if (!"getConnection".equals(name)) {
                    return deny(name);
                }
                Optional<JdbcRecording> recording = JdbcRecording.current();
                if (recording.isEmpty() || recording.get().phase() != JdbcRecording.Phase.CALL) {
                    return deny(name);
                }
                return connection(target);
            }
        });
    }

    private static Connection connection(Target target) {
        return proxy(Connection.class, new Handler("Connection", target) {
            @Override
            Object answer(Object self, String name, Object[] args) throws SQLException {
                if (NO_OPS.contains(name)) {
                    return null;
                }
                if (STATEMENTS.contains(name)) {
                    String sql = args == null || args.length == 0 || !(args[0] instanceof String s) ? "" : s;
                    JdbcRecording.current().ifPresent(recording -> recording.statement(label(name), sql));
                    throw new StatementReached("Dry run: " + label(name) + " reached a statement and ran nothing: "
                            + sql);
                }
                return switch (name) {
                    case "getAutoCommit", "isClosed", "isReadOnly" -> false;
                    case "isValid" -> true;
                    case "getWarnings", "getSchema", "getCatalog" -> null;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "getHoldability" -> java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;
                    case "getMetaData" -> metaData(target);
                    default -> deny(name);
                };
            }
        });
    }

    /** The stub's stop at a statement, which a call's classification finds in the cause chain (R-INS-11). */
    static final class StatementReached extends SQLException implements Stop {
        private static final long serialVersionUID = 1L;

        StatementReached(String message) {
            super(message);
        }
    }

    private static DatabaseMetaData metaData(Target target) {
        return proxy(DatabaseMetaData.class, new Handler("DatabaseMetaData", target) {
            @Override
            Object answer(Object self, String name, Object[] args) throws SQLException {
                Optional<?> known = switch (name) {
                    case "getDatabaseProductName" -> Optional.of(TargetFacts.productName(target));
                    case "getDatabaseProductVersion" -> target.version();
                    case "getDatabaseMajorVersion" -> TargetFacts.majorVersion(target);
                    case "getDatabaseMinorVersion" -> TargetFacts.minorVersion(target);
                    default -> Optional.empty();
                };
                return known.isPresent() ? known.get() : deny(name);
            }
        });
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(StubDataSource.class.getClassLoader(), new Class<?>[] {type},
                handler));
    }

    /** The {@code Object} and wrapper methods of every stub, then the type's own answers. */
    private abstract static class Handler implements InvocationHandler {
        private final String type;
        private final Target target;

        Handler(String type, Target target) {
            this.type = type;
            this.target = target;
        }

        @Override
        public final Object invoke(Object self, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> "Dry-run " + type + " for " + target;
                };
            }
            if ("isWrapperFor".equals(name)) {
                return ((Class<?>) args[0]).isInstance(self);
            }
            if ("unwrap".equals(name)) {
                Class<?> wanted = (Class<?>) args[0];
                return wanted.isInstance(self) ? self : deny("unwrap(" + wanted.getName() + ")");
            }
            return answer(self, name, args);
        }

        abstract Object answer(Object self, String name, Object[] args) throws SQLException;

        String label(String name) {
            return type + "." + name;
        }

        /** Records {@code name} on the thread's recording and throws: the stub reaches no database. */
        Object deny(String name) throws SQLException {
            JdbcRecording.current().ifPresent(recording -> recording.denied(label(name)));
            throw new Denied("Dry run: " + label(name) + " reaches the database, denied for "
                    + target);
        }
    }

    /** The refusal of a stubbed JDBC call, so a classifier can tell it from a provider's own failure. */
    static final class Denied extends SQLFeatureNotSupportedException implements Stop {
        private static final long serialVersionUID = 1L;

        Denied(String reason) {
            super(reason);
        }
    }

    /** Marks the stub's own stops, a denial or a reached statement, as told from a provider's failure. */
    interface Stop {}
}
