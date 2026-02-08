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
    /** Default trace limit when query has no explicit LIMIT t:N (ProcessM defaults to 30) */
    var defaultTraceLimit: Int = 30

    /** Event attributes to exclude from SELECT * output (ProcessM omits these) */
    var excludeEventAttrsInSelectStar: List<String> = listOf("concept:name", "cost:currency")
}
