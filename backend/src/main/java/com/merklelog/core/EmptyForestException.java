package com.merklelog.core;

/**
 * Thrown when a proof is requested from a forest that holds no entries.
 *
 * <p>An empty forest has a well-defined root — {@link Hashing#emptyTreeHash()} — so simply
 * <em>existing</em> while empty is an ordinary state, not an error. Asking it to prove the
 * inclusion of an entry it does not have is different: there is no honest answer, and
 * returning {@code false} or {@code null} would let a caller mistake "nothing to prove"
 * for "verification failed", which are very different things for an audit tool to report.
 *
 * <p>Unchecked rather than checked, because reaching it means the caller skipped a check it
 * could have made ({@code forest.isEmpty()}), which is a programming error rather than a
 * condition to be recovered from at every call site. The Phase 2 API layer maps it to a
 * 404-style response in one place.
 */
public class EmptyForestException extends IllegalStateException {

    public EmptyForestException(String message) {
        super(message);
    }
}
