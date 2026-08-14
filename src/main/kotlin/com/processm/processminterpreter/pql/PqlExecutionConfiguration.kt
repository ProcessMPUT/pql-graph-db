package com.processm.processminterpreter.pql

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class PqlExecutionConfiguration {
    @Bean(name = ["pqlPerLogExecutor"], destroyMethod = "shutdown")
    fun pqlPerLogExecutor(): ExecutorService =
        Executors.newFixedThreadPool(PER_LOG_PARALLELISM)

    private companion object {
        /** Concurrent per-log query fan-out width for classifier queries spanning many logs. */
        const val PER_LOG_PARALLELISM = 4
    }
}
