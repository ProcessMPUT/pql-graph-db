package com.processm.processminterpreter.pql

import QLLexer
import QLParser
import com.processm.processminterpreter.pql.visitor.PQLErrorListener
import com.processm.processminterpreter.pql.visitor.QLToCypherVisitor
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.slf4j.LoggerFactory
import com.processm.processminterpreter.config.ProcessMConfig
import org.springframework.stereotype.Component

/**
 * ANTLR-based PQL to Cypher translator
 *
 * Uses ANTLR4 parser with ProcessM's official QL.g4 grammar for parsing PQL queries
 * and translating them to Neo4j Cypher.
 */
@Component
class AntlrPQLTranslator(
    private val processMConfig: ProcessMConfig
) : PQLTranslator {

    private val logger = LoggerFactory.getLogger(AntlrPQLTranslator::class.java)

    /**
     * Translate PQL query to Cypher query using ANTLR parser
     *
     * @param pqlQuery The PQL query string
     * @param logId Optional log ID to filter results
     * @return CypherQuery object containing the Cypher query string and parameters
     * @throws IllegalArgumentException if the PQL query has syntax errors
     */
    override fun translateToCypher(pqlQuery: String, logId: String?, classifiers: Map<String, List<String>>, defaultTraceLimit: Int?): CypherQuery {
        logger.debug("Translating PQL query using ANTLR: $pqlQuery")

        try {
            // Step 1: Create input stream from PQL query string
            val input = CharStreams.fromString(pqlQuery)

            // Step 2: Create lexer (tokenizer)
            val lexer = QLLexer(input)

            // Step 3: Create token stream
            val tokens = CommonTokenStream(lexer)

            // Step 4: Create parser
            val parser = QLParser(tokens)

            // Step 5: Add custom error listener for better error messages
            val errorListener = PQLErrorListener()
            parser.removeErrorListeners() // Remove default console error listener
            parser.addErrorListener(errorListener)

            // Step 6: Parse the query and build AST (Abstract Syntax Tree)
            val tree = parser.query()

            // Step 7: Check for syntax errors
            errorListener.throwIfErrors()

            // Step 8: Visit the AST and generate Cypher query
            // defaultTraceLimit: null = use config default, -1 = no limit, >0 = explicit limit
            val effectiveTraceLimit = when (defaultTraceLimit) {
                null -> processMConfig.defaultTraceLimit
                -1 -> null  // no default limit (used by tests to match ProcessM test behavior)
                else -> defaultTraceLimit
            }
            val visitor = QLToCypherVisitor(logId, effectiveTraceLimit, classifiers)
            val result = visitor.visit(tree) as CypherQuery

            logger.debug("Generated Cypher: ${result.query}")
            logger.debug("Parameters: ${result.parameters}")

            return result
        } catch (e: IllegalArgumentException) {
            logger.error("PQL parsing error: ${e.message}")
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error during PQL translation", e)
            throw IllegalArgumentException("Failed to parse PQL query: ${e.message}", e)
        }
    }
}
