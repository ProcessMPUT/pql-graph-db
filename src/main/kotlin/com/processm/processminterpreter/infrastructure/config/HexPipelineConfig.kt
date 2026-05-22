package com.processm.processminterpreter.infrastructure.config

import com.processm.processminterpreter.application.query.PqlParser
import com.processm.processminterpreter.application.ports.QueryPlanExecutor
import com.processm.processminterpreter.infrastructure.parser.antlr.AntlrPqlParser
import com.processm.processminterpreter.infrastructure.parser.antlr.AstBuilder
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher.CypherCodegen
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.CypherTypeMapper
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.result.HierarchyReconstructor
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.Neo4jQueryPlanExecutor
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher.PhysicalAttributeMapper
import org.neo4j.driver.Driver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the hex-architecture pipeline adapters into the Spring context.
 *
 * Kept as explicit `@Bean` methods (rather than `@Component` scattered across
 * each infra class) so the composition root stays in one place and the new
 * adapters stay plain Kotlin classes — easy to instantiate in unit tests
 * without Spring magic.
 *
 * Port → adapter mappings:
 *  - [PqlParser]           → [AntlrPqlParser]
 *  - [QueryPlanExecutor]   → [Neo4jQueryPlanExecutor]
 *
 * Other ports ([com.processm.processminterpreter.application.ports.LogRepository],
 * [com.processm.processminterpreter.application.ports.XesWriter],
 * [com.processm.processminterpreter.application.ports.LogDataImporter]) are wired via
 * `@Repository` / `@Component` on their adapter classes directly.
 */
@Configuration
class HexPipelineConfig {

    // ----- Parser -----

    @Bean
    fun astBuilder(): AstBuilder = AstBuilder()

    @Bean
    fun pqlParser(astBuilder: AstBuilder): PqlParser = AntlrPqlParser(astBuilder)

    // ----- Codegen primitives -----

    @Bean
    fun physicalAttributeMapper(): PhysicalAttributeMapper = PhysicalAttributeMapper()

    @Bean
    fun cypherCodegen(mapper: PhysicalAttributeMapper): CypherCodegen = CypherCodegen(mapper)

    @Bean
    fun cypherTypeMapper(): CypherTypeMapper = CypherTypeMapper()

    @Bean
    fun hierarchyReconstructor(): HierarchyReconstructor = HierarchyReconstructor()

    // ----- Executor -----

    @Bean
    fun queryPlanExecutor(
        driver: Driver,
        codegen: CypherCodegen,
        typeMapper: CypherTypeMapper,
        reconstructor: HierarchyReconstructor,
    ): QueryPlanExecutor = Neo4jQueryPlanExecutor(
        driver = driver,
        codegen = codegen,
        typeMapper = typeMapper,
        reconstructor = reconstructor,
    )
}
