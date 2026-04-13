package com.processm.processminterpreter

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun neo4jContainer(): Neo4jContainer<*> =
        Neo4jContainer(DockerImageName.parse("neo4j:5.15-community"))
            .withAdminPassword("password123")
            .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")
            .withEnv("NEO4J_dbms_security_procedures_unrestricted", "apoc.*")
            .withEnv("NEO4J_dbms_memory_heap_initial__size", "512m")
            .withEnv("NEO4J_dbms_memory_heap_max__size", "1G")
}
