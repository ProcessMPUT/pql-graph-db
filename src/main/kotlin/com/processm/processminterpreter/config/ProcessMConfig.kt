package com.processm.processminterpreter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Configuration properties for ProcessM compatibility settings.
 * Prefix: processm.compatibility
 */
@Configuration
@ConfigurationProperties(prefix = "processm.compatibility")
class ProcessMConfig {
    /**
     * Default trace limit settings.
     * ProcessM REST API caps traces at 30 via LogsService.applyLimits().
     * Enable this to match that behavior; disable to return all traces.
     */
    var defaultTraceLimit: DefaultTraceLimit = DefaultTraceLimit()

    class DefaultTraceLimit {
        /** Whether to apply the default trace limit */
        var enabled: Boolean = true

        /** Maximum number of traces returned when no explicit LIMIT is set (or explicit LIMIT exceeds this) */
        var limit: Int = 30
    }
}
