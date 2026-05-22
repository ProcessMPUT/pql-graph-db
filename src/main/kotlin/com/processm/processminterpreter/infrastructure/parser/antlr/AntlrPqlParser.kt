package com.processm.processminterpreter.infrastructure.parser.antlr

import QLLexer
import QLParser
import com.processm.processminterpreter.domain.pql.syntax.RawQuery
import com.processm.processminterpreter.application.query.PqlParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

/**
 * [PqlParser] backed by the ANTLR-generated [QLLexer] + [QLParser] pair.
 *
 * Pipeline: `String -> CharStream -> QLLexer -> TokenStream -> QLParser -> AstBuilder -> RawQuery`.
 * Collects syntax errors through [PQLErrorListener]; throws the first accumulated
 * error after parsing completes.
 */
class AntlrPqlParser(
    private val astBuilder: AstBuilder = AstBuilder(),
) : PqlParser {

    override fun parse(source: String): RawQuery {
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
