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

    fun isCriticalFailure(row: ContainerIoBenchmarkResult, localAppContainer: String): Boolean {
        if (row.status == STATUS_COUNTER_RESET) return true
        if (row.blockReadBytes == null || row.blockWriteBytes == null) return true
        val networkMissing = row.networkReceiveBytes == null || row.networkTransmitBytes == null
        if (networkMissing) {
            if (isApplicationBoundary(row, localAppContainer)) return true
            return !isDiagnosticPartial(row, localAppContainer)
        }
        return row.status !in setOf(STATUS_OK, STATUS_PARTIAL)
    }

    fun isDiagnosticPartial(row: ContainerIoBenchmarkResult, localAppContainer: String): Boolean =
        row.status == STATUS_UNAVAILABLE &&
            row.blockReadBytes != null &&
            row.blockWriteBytes != null &&
            !isApplicationBoundary(row, localAppContainer) &&
            (row.networkReceiveBytes == null || row.networkTransmitBytes == null)

    fun hasUsableBlockCounters(row: ContainerIoBenchmarkResult): Boolean =
        row.status != STATUS_COUNTER_RESET && row.blockReadBytes != null && row.blockWriteBytes != null

    private fun isApplicationBoundary(row: ContainerIoBenchmarkResult, localAppContainer: String): Boolean =
        when (row.system) {
            "local" -> row.component == localAppContainer
            // The reference application and PostgreSQL share its sole measured container.
            "reference" -> true
            else -> true
        }
}
