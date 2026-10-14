package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * One call the dry run makes on every target: an executor of {@code root} handed to {@code body}, in a stub
 * transaction unless {@code outsideTransaction}. {@code firstStatementOnly} tells, per target factory and profile,
 * whether a statement after the first could still be refused; a {@code call}'s never does, a sweep's reads the
 * definition's public facts.
 *
 * @implSpec R-INS-11, R-INS-12
 */
@Incubating
public final class DryCall {

    private final String label;
    private final Class<?> root;
    private final Function<ModelQueryExecutor<Object>, ?> body;
    private final boolean outsideTransaction;
    private final BiPredicate<EntityManagerFactory, VendorProfile> firstStatementOnly;

    private DryCall(String label, Class<?> root, Function<ModelQueryExecutor<Object>, ?> body,
            boolean outsideTransaction, BiPredicate<EntityManagerFactory, VendorProfile> firstStatementOnly) {
        this.label = Objects.requireNonNull(label, "label");
        this.root = Objects.requireNonNull(root, "root");
        this.body = Objects.requireNonNull(body, "body");
        this.outsideTransaction = outsideTransaction;
        this.firstStatementOnly = Objects.requireNonNull(firstStatementOnly, "firstStatementOnly");
    }

    /** A call of {@code body} on an executor of {@code root}, reported with no qualifier. */
    @SuppressWarnings("unchecked")
    public static <E> DryCall of(String label, Class<E> root, Function<ModelQueryExecutor<E>, ?> body,
            boolean outsideTransaction) {
        Function<?, ?> erased = Objects.requireNonNull(body, "body");
        return new DryCall(label, root, (Function<ModelQueryExecutor<Object>, ?>) erased, outsideTransaction,
                (emf, profile) -> false);
    }

    /** A sweep's call, in a stub transaction, marked by {@code firstStatementOnly}. */
    static DryCall swept(String label, Class<?> root, Function<ModelQueryExecutor<Object>, ?> body,
            BiPredicate<EntityManagerFactory, VendorProfile> firstStatementOnly) {
        return new DryCall(label, root, body, false, firstStatementOnly);
    }

    /** The label of its report lines. */
    public String label() {
        return label;
    }

    Class<?> root() {
        return root;
    }

    Function<ModelQueryExecutor<Object>, ?> body() {
        return body;
    }

    boolean outsideTransaction() {
        return outsideTransaction;
    }

    boolean firstStatementOnly(EntityManagerFactory emf, VendorProfile profile) {
        return firstStatementOnly.test(emf, profile);
    }

    @Override
    public String toString() {
        return label;
    }
}
