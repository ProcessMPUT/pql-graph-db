package com.processm.processminterpreter.infrastructure.config

import com.processm.processminterpreter.domain.pql.plan.HierarchicalLimits
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
     * ProcessM REST API applies default limits through LogsService.applyLimits().
     * Explicit query limits are capped by these values; missing limits are filled in.
     */
    var defaultLimits: DefaultLimits = DefaultLimits()

    class DefaultLimits {
        var enabled: Boolean = true
        var log: Long = 10
        var trace: Long = 30
        var event: Long = 90

        fun toHierarchicalLimits(): HierarchicalLimits =
            if (enabled) {
                HierarchicalLimits(log = log, trace = trace, event = event)
            } else {
                HierarchicalLimits()
            }
    }
}


