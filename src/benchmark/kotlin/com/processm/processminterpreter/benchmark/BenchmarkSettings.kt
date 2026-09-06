package com.processm.processminterpreter.benchmark

/** Connection settings only. The study job owns all measurement parameters. */
data class BenchmarkSettings(
    val localApi: String,
    val referenceApi: String,
    val processMLogin: String,
    val processMPassword: String,
) {
    companion object {
        fun fromEnvironment(): BenchmarkSettings {
            fun env(name: String, default: String) = System.getenv(name) ?: System.getProperty(name) ?: default
            return BenchmarkSettings(
                env("LOCAL_PROCESSM_API", "http://localhost:8080/api").trimEnd('/'),
                env("REFERENCE_PROCESSM_API", "http://localhost:80/api").trimEnd('/'),
                env("PROCESSM_LOGIN", "admin@example.com"), env("PROCESSM_PASSWORD", "Admin1234"),
            )
        }
    }
}
