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
        Neo4jContainer(DockerImageName.parse("neo4j:5.26.25-community-ubi10"))
            .withAdminPassword("password123")
            .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")
            .withEnv("NEO4J_dbms_security_procedures_unrestricted", "apoc.*")
            .withEnv("NEO4J_server_memory_heap_initial__size", "1G")
            .withEnv("NEO4J_server_memory_heap_max__size", "6G")
            .withEnv("NEO4J_server_memory_pagecache_size", "512m")
            .withEnv("NEO4J_db_memory_transaction_total_max", "8G")
}
