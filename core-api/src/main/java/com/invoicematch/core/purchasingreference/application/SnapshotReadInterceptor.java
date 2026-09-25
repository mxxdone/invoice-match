package com.invoicematch.core.purchasingreference.application;

/**
 * Internal test seam invoked after the root snapshot row has been read and
 * before the child rows are read. It lets a test interleave an external refresh
 * commit inside the read transaction to prove the read uses one consistent
 * snapshot. Production uses {@link #NONE}; the type is package-private so it
 * does not become part of the feature's public surface.
 */
@FunctionalInterface
interface SnapshotReadInterceptor {

    SnapshotReadInterceptor NONE = () -> { };

    void afterRootLoaded();
}
