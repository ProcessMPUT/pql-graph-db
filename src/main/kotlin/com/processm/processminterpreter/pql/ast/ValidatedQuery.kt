package com.processm.processminterpreter.pql.ast

/**
 * A [PqlQuery] that has been run through the Validator phase without error.
 *
 * The Planner accepts only [ValidatedQuery], not a bare [PqlQuery] — this is a
 * compile-time safety net enforcing that validation has run. The validator is the
 * sole constructor path in production; tests may construct directly.
 */
@JvmInline
value class ValidatedQuery(val query: PqlQuery)
