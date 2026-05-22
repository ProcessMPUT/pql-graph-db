package com.processm.processminterpreter.domain.pql.catalog

/**
 * What kind of attribute a PQL reference resolves to.
 *
 *  - [STANDARD]   — XES IEEE 1849-2016 standard attribute (concept:name, time:timestamp, etc.)
 *  - [CUSTOM]     — user-defined attribute, referenced in brackets (e.g. `[myAttr]`)
 *  - [CLASSIFIER] — classifier reference (e.g. `c:Activity classifier`)
 */
enum class AttributeKind { STANDARD, CUSTOM, CLASSIFIER }
