package com.processm.processminterpreter.pql.error

/**
 * Taxonomy of PQL compile-time problems. Mirrors ProcessM's `PQLSyntaxException.Problem`
 * (https://github.com/ProcessMPUT/processm) to preserve compatibility. Extend this list
 * only when adding new diagnostics — removing or renaming entries breaks test parity
 * with ProcessM and any downstream client that switches on the enum name.
 *
 * Entries marked **reserved** are declared for ProcessM compatibility but not currently
 * raised by this implementation. Either ProcessM raises them in code paths we do not
 * reproduce, or the validation has not been ported yet. Keep them — never replace with
 * `SyntaxError` ad-hoc, or the wire format diverges.
 */
enum class Problem {
    // Parser / structural
    SyntaxError,
    UnexpectedChild,          // reserved
    DuplicateLimit,           // reserved (parser accepts the last occurrence)
    DuplicateOffset,          // reserved (parser accepts the last occurrence)
    PositiveIntegerRequired,
    DecimalPartDropped,       // reserved

    // Attribute / scope
    ScopeRequired,
    ScopeHoistingInSelectOrOrderBy, // reserved
    NoHoistingBeyondLong,
    UnknownAttributeType,     // reserved
    NoSuchAttribute,
    MixedScopes,

    // SELECT / GROUP BY rules
    ExplicitSelectAllWithImplicitGroupBy, // reserved (covered by MixedScopes today)
    MissingAttributesInAggregation,       // reserved
    SelectAllConflictsWithReferencingByName, // reserved
    AttributeNotInGroupBy,
    OrderByClauseRemoved,     // reserved

    // Classifiers
    ClassifierInWhere,
    ClassifierOnLog,
    InvalidUseOfClassifiers,

    // Aggregation
    AggregationFunctionInWhere,

    // Literal validation
    InvalidBoolean,
    InvalidNumber,
    InvalidDateTime,
    InvalidUUID,

    Unknown,
}
