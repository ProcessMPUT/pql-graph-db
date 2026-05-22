package com.processm.processminterpreter.domain.pql.error

/**
 * Taxonomy of PQL compile-time problems. Mirrors ProcessM's `PQLSyntaxException.Problem`
 * (https://github.com/ProcessMPUT/processm) to preserve compatibility. Extend this list
 * only when adding new diagnostics — removing or renaming entries breaks test parity.
 */
enum class Problem {
    // Parser / structural
    SyntaxError,
    UnexpectedChild,
    DuplicateLimit,
    DuplicateOffset,
    PositiveIntegerRequired,
    DecimalPartDropped,

    // Attribute / scope
    ScopeRequired,
    ScopeHoistingInSelectOrOrderBy,
    NoHoistingBeyondLong,
    UnknownAttributeType,
    NoSuchAttribute,
    MixedScopes,

    // SELECT / GROUP BY rules
    ExplicitSelectAllWithImplicitGroupBy,
    MissingAttributesInAggregation,
    SelectAllConflictsWithReferencingByName,
    AttributeNotInGroupBy,
    OrderByClauseRemoved,

    // Classifiers
    ClassifierInWhere,
    ClassifierOnLog,

    // Aggregation
    AggregationFunctionInWhere,

    // Literal validation
    InvalidBoolean,
    InvalidNumber,
    InvalidDateTime,
    InvalidUUID,
    InvalidUseOfClassifiers,

    Unknown,
}
