package com.processm.processminterpreter.benchmark

/** A failed scientific control is not a transient infrastructure fault to retry until it passes. */
class StudyDataMismatch(message: String) : IllegalStateException(message)
