package com.processm.processminterpreter.benchmark

/**
 * Separates metrics required by the report from diagnostic counters exposed by
 * the deployment topology. Block I/O is required for every measured component;
 * Network I/O is required only at the HTTP application's container boundary.
 */
internal object ContainerIoValidity {
    const val STATUS_OK = "OK"
    const val STATUS_PARTIAL = "PARTIAL"
    const val STATUS_UNAVAILABLE = "UNAVAILABLE"
    const val STATUS_COUNTER_RESET = "COUNTER_RESET"
}
