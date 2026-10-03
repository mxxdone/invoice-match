package com.invoicematch.core.analysis.application;

/**
 * Fixed, bounded classification codes for a failed publish attempt. Only these
 * codes are ever stored in {@code last_error_code} or logged; the raw SDK
 * exception message, payload and credentials are never exposed.
 */
public enum AnalysisPublishError {
    /** Relay is disabled, so no broker call is attempted. */
    RELAY_DISABLED,
    /** The single execution slot already owns an attempt; the call was not queued. */
    SLOT_BUSY,
    /** The publisher was closed; no new attempt is admitted. */
    RELAY_CLOSED,
    /** Own connection/channel teardown did not complete within the cleanup budget. */
    CLEANUP_FAILED,
    /** TCP connect / DNS / broker handshake could not be established. */
    CONNECT_FAILED,
    /** The overall monotonic attempt deadline elapsed before completion. */
    PUBLISH_TIMEOUT,
    /** Publisher confirm was not received within the remaining budget. */
    CONFIRM_TIMEOUT,
    /** The broker NACKed the published message. */
    CONFIRM_NACK,
    /** The mandatory message was returned as unroutable (ACK is not success). */
    MANDATORY_RETURN,
    /** Channel declaration/publish failed. */
    CHANNEL_FAILED,
    /** Any other I/O failure. */
    IO_FAILED
}
