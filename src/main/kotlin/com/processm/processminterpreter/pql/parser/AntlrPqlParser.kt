package com.processm.processminterpreter.pql.parser

import QLLexer
import QLParser
import com.processm.processminterpreter.pql.ast.PqlQuery
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.springframework.stereotype.Component

/**
 * PQL parser backed by the ANTLR-generated [QLLexer] + [QLParser] pair.
 *
 * Pipeline: `String -> CharStream -> QLLexer -> TokenStream -> QLParser -> AstBuilder -> PqlQuery`.
 * Collects syntax errors through [PQLErrorListener]; throws the first accumulated
 * error after parsing completes.
 */
@Component
class AntlrPqlParser(
    private val astBuilder: AstBuilder = AstBuilder(),
) {

    fun parse(source: String): PqlQuery {
        val lexer = QLLexer(CharStreams.fromString(source))
        val lexerErrors = PQLErrorListener()
        lexer.removeErrorListeners()
        lexer.addErrorListener(lexerErrors)

        val tokens = CommonTokenStream(lexer)
        val parser = QLParser(tokens)
        val parserErrors = PQLErrorListener()
        parser.removeErrorListeners()
        parser.addErrorListener(parserErrors)

        val tree = parser.query()

        lexerErrors.throwIfAny()
        parserErrors.throwIfAny()

        return astBuilder.build(tree)
    }
}
